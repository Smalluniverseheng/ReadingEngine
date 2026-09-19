package io.legado.app.engine

import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.EngineSearchController
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.savePreservingCustomCoverUrl
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地阅读引擎服务端 —— 局域网内的阅读前端按 THP/1 协议直接调用本引擎。
 *
 * 端口 1234, 仅局域网监听, 无账号鉴权。
 *
 * 规范端点(THP/1.0 §6/§7.3, 推荐, 前端优先走这条):
 *   GET  /thp/meta                            → {ok:true,data:{protocol,role,caps,…}}
 *   POST /thp/m/{module}/search               → {ok:true,data:[{id,name,author,coverUrl,intro,ref}]}
 *   POST /thp/m/{module}/toc                  → {ok:true,data:[{id,name,index}]}
 *   POST /thp/m/{module}/content              → {ok:true,data:{…}}  形状按模块:
 *          novel→{text}  comic→{images:[…]}  music→{url,variants}  video→{url,header,variants}
 *   (以上三个同时支持 GET 版: ?q= / ?id= / ?id=&chapterId=)
 *   module ∈ novel | comic | music | video
 *
 * 兼容端点(旧草稿, 保留以支持老客户端):
 *   GET /thp/search?type=novel&q=…            → {object:"list",data:{items:[…]}}
 *   GET /thp/chapters?type=…&id=…             → 同上
 *   GET /thp/content?type=…&id=…&chapter=…    → {object:"novel-content",data:{text}}
 *   GET /thp/discover?type=…                  → 发现页: 各书源的分类标签
 *   GET /thp/explore?type=…&source=…&url=…&page=… → 发现列表
 *
 * 搜索/目录/正文全部委托 Legado 本体(EngineSearchController / BookController / WebBook),
 * 规则解析 100% 由引擎完成, 本层只做协议转换。
 */
class ThpServer(port: Int = 1234) : NanoHTTPD(port) {

    companion object {
        const val PORT = 1234

        /**
         * 引擎版本。★取构建期 versionName，不再手写常量 ——
         * 旧实现把这里写死成 "1.5.0"，发到 1.5.3 时 meta.version 仍报 1.5.0，
         * 前端据此判断引擎能力/兼容性会判错。用 BuildConfig 使其永不漂移。
         * （不能用 const：BuildConfig 字段是 Java static final，Kotlin 不认作编译期常量。）
         */
        val VERSION: String = io.legado.app.BuildConfig.VERSION_NAME

        const val PROTOCOL = "THP/1.0"

        /** §8: limit 默认 20、最大 100（超限截断不报错） */
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 100

        /**
         * 规范端点搜索的时间预算(秒)。
         * 依据 THP §4：单端点响应建议 ≤15s，而资源库聚合搜索给单 peer 的超时是 **8s**。
         * 引擎旧默认预算是 25s —— 超过调用方超时，等于引擎的规范搜索**永远被判定失败**。
         * 这里压到 7s，留 1s 网络余量，保证结果能在调用方超时前返回（聚合搜索允许部分结果）。
         */
        private const val SPEC_SEARCH_TIMEOUT_SEC = 7L

        /** discover/explore 遍历全部源的总预算(ms)，防止"源多时单个请求跑几分钟" */
        private const val DISCOVER_BUDGET_MS = 12_000L

        /** 请求体上限。自己读原始字节就绕过了 NanoHTTPD 的体积保护，故在此补一道。 */
        private const val MAX_BODY_BYTES = 1 * 1024 * 1024

        private var instance: ThpServer? = null

        /** 模块名 → legado 书源类型(THP §6.1) */
        private val MODULES = mapOf(
            "novel" to 0,   // text
            "music" to 1,   // audio
            "comic" to 2,   // image
            "video" to 4,   // video
        )

        /**
         * 旧草稿端点集合。这些端点的**请求**形状与**响应**形状都是历史遗留：
         * 请求走 query 而非 JSON body，响应走 {object,data} 而非 THP 信封。
         * v2/v4 后端的 engineCall() 降级链在最后一步读它们，并靠 `object == "error"` 判错，
         * 所以它们的错误响应必须保持旧形状，不能一并改成 THP 信封。
         */
        private val LEGACY_ENDPOINTS = setOf(
            "/thp/search", "/thp/chapters", "/thp/content", "/thp/discover", "/thp/explore"
        )

        @Synchronized
        fun ensureStarted() {
            if (instance?.isAlive == true) return
            runCatching {
                instance = ThpServer(PORT).apply { start(SOCKET_READ_TIMEOUT, false) }
            }
        }

        @Synchronized
        fun stop() {
            runCatching { instance?.stop() }
            instance = null
        }
    }

    /** 搜索结果缓存: bookUrl → 归一化字段(目录阶段据此补登入库) */
    private val searchCache = ConcurrentHashMap<String, Map<String, Any?>>()

    /**
     * 当前请求的 X-TH-Request-Id（THP §3.1 要求原样回显）。
     * NanoHTTPD 逐请求在工作线程上跑 serve()，故用 ThreadLocal 传递到 json()。
     * serve() 每次无条件 set(可能为 null)，不会残留上一个请求的值。
     */
    private val requestId = ThreadLocal<String?>()

    private val instanceId: String by lazy { EngineBeacon.instanceId() }

    // ─────────────────────────── 路由 ───────────────────────────

    override fun serve(session: IHTTPSession): Response {
        // THP §3.1: 原样回显调用方的 X-TH-Request-Id（无条件 set，避免 ThreadLocal 残留上个请求的值）
        val hdrs = session.headers
        requestId.set(if (hdrs == null) null else hdrs["x-th-request-id"])

        val uri = session.uri
        if (uri == null) return json(400, errSpec("INVALID_REQUEST", "缺 uri"))
        if (!uri.startsWith("/thp")) return json(404, errSpec("NOT_FOUND", "未知端点"))

        // 取参: POST 走 JSON body, 否则走 query
        val q = session.parms ?: emptyMap()
        val body: JSONObject? = if (session.method == Method.POST) readJsonBody(session) else null
        val get: (String) -> String? = { k -> body?.optString(k)?.takeIf { it.isNotEmpty() } ?: q[k] }

        // 旧草稿端点必须继续吐旧信封(老客户端按 object:"error" 判错，且 v2/v4 后端在降级链里读它)；
        // 规范端点一律走 THP 信封 —— THP §3.2「失败判断唯一依据 ok:false」。
        val legacyPath = uri in LEGACY_ENDPOINTS

        return try {
            when {
                uri == "/thp/meta" -> meta()

                // ── 规范端点 /thp/m/{module}/{op} ──
                REGEX_MODULE.matches(uri) -> {
                    val m = REGEX_MODULE.find(uri)!!
                    val module = m.groupValues[1]
                    val op = m.groupValues[2]
                    if (!MODULES.containsKey(module)) {
                        json(404, errSpec("NOT_FOUND", "未注册的模块: $module"))
                    } else when (op) {
                        "search" -> specSearch(module, get)
                        "toc" -> specToc(module, get)
                        "content" -> specContent(module, get)
                        else -> json(404, errSpec("UNSUPPORTED", "模块 $module 不支持 $op"))
                    }
                }

                // ── 兼容端点(旧草稿) ──
                uri == "/thp/search" -> search(q)
                uri == "/thp/chapters" -> chapters(q)
                uri == "/thp/content" -> legacyContent(q)
                uri == "/thp/discover" -> discover(q)
                uri == "/thp/explore" -> explore(q)

                // 未知端点也必须是 THP 信封(THP-SDK §6 自测第 4 条)
                else -> json(404, errSpec("NOT_FOUND", "未知端点"))
            }
        } catch (e: Exception) {
            if (legacyPath) json(500, err("engine_error", e.message ?: "引擎内部错误"))
            // 规范化: 错误码用注册表里的 UPSTREAM_FAIL, 不用自造的 UPSTREAM_ERROR(§10)
            else json(500, errSpec("UPSTREAM_FAIL", e.message ?: "引擎内部错误"))
        }
    }

    // ─────────────────────────── 规范端点 ───────────────────────────

    private fun meta(): Response = json(200, okSpec(JSONObject()
        .put("protocol", PROTOCOL)
        .put("instanceId", instanceId)
        .put("role", "engine")
        .put("name", EngineBeacon.NAME)
        .put("version", VERSION)
        .put("vendor", "reading-engine")
        // §11 caps: 声明 post-query —— 三个规范端点都实现了 POST 版;
        // 不声明调用方会按 §7.3 优先 POST 却认为该引擎不支持 POST, 造成能力声明与实际不符。
        .put("caps", JSONArray().put("m:novel").put("m:comic").put("m:music").put("m:video").put("post-query"))
        .put("auth", JSONArray().put("none"))
        .put("remote", false)
        .put("endpoints", JSONArray().put("search").put("toc").put("content"))
        .put("deprecated", JSONArray())
        .put("ext", JSONObject())))

    private fun specSearch(module: String, get: (String) -> String?): Response {
        val q = (get("q") ?: get("key"))?.trim()
        if (q.isNullOrEmpty()) return json(400, errSpec("INVALID_REQUEST", "缺参数 q"))
        // §8: limit 默认 20、最大 100，超限自动截断不报错（旧实现默认 50 且无上限）
        val limit = (get("limit")?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        // ★ 时间预算必须小于调用方超时(§4: 资源库聚合给单 peer 8s)，否则「永远超时」
        val rd = EngineSearchController.search(
            mapOf("key" to listOf(q)), timeoutSec = SPEC_SEARCH_TIMEOUT_SEC)
        if (!rd.isSuccess) return json(502, errSpec("UPSTREAM_FAIL", rd.errorMsg ?: "搜索失败"))
        @Suppress("UNCHECKED_CAST")
        val raw = (rd.data as? List<Map<String, Any?>>) ?: emptyList()
        val arr = JSONArray()
        var n = 0
        val seen = HashSet<String>()
        for (b in raw) {
            if (n >= limit) break
            val st = (b["sourceType"] as? Int) ?: 0
            if (st != MODULES[module]) continue
            val bookUrl = (b["bookUrl"] as? String) ?: continue
            // 协议边界最后一道闸: 不干净的书 id 不出 THP(理由见 isUsableBookUrl)
            if (!isUsableBookUrl(bookUrl)) continue
            if (!seen.add(bookUrl)) continue
            cachePut(bookUrl, b)
            arr.put(JSONObject()
                .put("id", bookUrl)
                .put("name", b["name"] ?: "")
                .put("author", b["author"] ?: "")
                .put("coverUrl", b["coverUrl"] ?: "")
                .put("intro", (b["intro"] as? String ?: "").take(200))
                .put("ref", bookUrl))
            n++
        }
        // §8 分页三件套: 引擎一轮返回全量, 故 cursor="" + hasMore=false
        return json(200, okSpec(arr, pageMeta(n)))
    }

    private fun specToc(module: String, get: (String) -> String?): Response {
        val id = get("id")
        if (id.isNullOrBlank()) return json(400, errSpec("INVALID_REQUEST", "缺参数 id"))
        val list = loadToc(id) ?: return json(502, errSpec("UPSTREAM_FAIL", "目录获取失败"))
        val arr = JSONArray()
        for (c in list) {
            arr.put(JSONObject()
                .put("id", c.url)
                .put("name", c.title)
                .put("index", c.index))
        }
        return json(200, okSpec(arr, pageMeta(list.size)))
    }

    private fun specContent(module: String, get: (String) -> String?): Response {
        val id = get("id")
        if (id.isNullOrBlank()) return json(400, errSpec("INVALID_REQUEST", "缺参数 id"))
        // 规范用 chapterId, 兼容 chapter
        val chapterRef = get("chapterId") ?: get("chapter")
        if (chapterRef.isNullOrBlank()) return json(400, errSpec("INVALID_REQUEST", "缺参数 chapterId"))
        val index = resolveChapterIndex(id, chapterRef)
            ?: return json(404, errSpec("NOT_FOUND", "章节不存在"))

        return when (module) {
            "novel" -> {
                val rd = BookController.getBookContent(
                    mapOf("url" to listOf(id), "index" to listOf(index.toString())))
                if (!rd.isSuccess) json(502, errSpec("UPSTREAM_FAIL", rd.errorMsg ?: "正文获取失败"))
                else json(200, okSpec(JSONObject().put("text", (rd.data as? String) ?: ""), baseMeta()))
            }
            "comic" -> {
                val loaded = loadRaw(id, index)
                    ?: return json(502, errSpec("UPSTREAM_FAIL", "图片列表获取失败"))
                val images = runBlocking {
                    runCatching {
                        withTimeoutOrNull(20_000) {
                            BookHelp.flowImages(loaded.chapter, loaded.raw).toList()
                        }
                    }.getOrNull()
                } ?: return json(502, errSpec("UPSTREAM_FAIL", "图片规则解析失败"))
                json(200, okSpec(JSONObject().put("images", JSONArray(images)), baseMeta()))
            }
            "music", "video" -> {
                val loaded = loadRaw(id, index)
                    ?: return json(502, errSpec("UPSTREAM_FAIL", "播放地址获取失败"))
                val mediaUrl = loaded.raw.trim()
                // 按官方播放器的方式解析: 章节内容 → AnalyzeUrl → 最终播放地址 + 请求头
                val analyzed = runBlocking {
                    runCatching {
                        AnalyzeUrl(
                            mediaUrl,
                            source = appDb.bookSourceDao.getBookSource(loaded.book.origin),
                            ruleData = loaded.book,
                            chapter = loaded.chapter,
                        )
                    }.getOrNull()
                }
                val resolved = analyzed?.url?.takeIf { it.isNotBlank() }
                    ?: mediaUrl.takeIf { it.startsWith("http") }
                    ?: return json(502, errSpec("UPSTREAM_FAIL", "播放地址解析失败"))
                val header = JSONObject()
                analyzed?.headerMap?.forEach { (k, v) -> header.put(k, v) }
                json(200, okSpec(JSONObject()
                    .put("url", resolved)
                    .put("header", header)
                    .put("variants", JSONArray()), baseMeta()))
            }
            else -> json(404, errSpec("UNSUPPORTED", "模块 $module 的内容暂不支持"))
        }
    }

    // ─────────────────────────── 兼容端点 ───────────────────────────

    private fun search(parms: Map<String, String>): Response {
        val q = parms["q"]?.trim()
        val type = parms["type"] ?: "novel"
        if (q.isNullOrEmpty()) return json(400, err("invalid_request", "缺参数 q"))
        val wantType = MODULES[type] ?: 0
        val rd = EngineSearchController.search(mapOf("key" to listOf(q)))
        if (!rd.isSuccess) return json(502, err("source_error", rd.errorMsg ?: "搜索失败"))
        @Suppress("UNCHECKED_CAST")
        val raw = (rd.data as? List<Map<String, Any?>>) ?: emptyList()
        val items = JSONArray()
        val seen = HashSet<String>()
        for (b in raw) {
            val st = (b["sourceType"] as? Int) ?: 0
            if (st != wantType) continue
            val bookUrl = (b["bookUrl"] as? String) ?: continue
            if (!isUsableBookUrl(bookUrl)) continue
            if (!seen.add(bookUrl)) continue
            cachePut(bookUrl, b)
            items.put(JSONObject()
                .put("id", bookUrl)
                .put("name", b["name"] ?: "")
                .put("author", b["author"] ?: "")
                .put("coverUrl", b["coverUrl"] ?: "")
                .put("intro", (b["intro"] as? String ?: "").take(200))
                .put("kind", b["kind"] ?: ""))
        }
        return json(200, JSONObject()
            .put("object", "list")
            // 顶层扁平数组: v2/v4 后端降级归一化优先读 j.items, 缺了会把 j.data 当数组用而抛错
            .put("items", items)
            .put("data", JSONObject().put("items", items)))
    }

    private fun chapters(parms: Map<String, String>): Response {
        val id = parms["id"]
        if (id.isNullOrBlank()) return json(400, err("invalid_request", "缺参数 id"))
        val list = loadToc(id) ?: return json(502, err("source_error", "目录获取失败"))
        val items = JSONArray()
        for (c in list) {
            items.put(JSONObject()
                .put("name", c.title)
                .put("url", c.url)
                .put("index", c.index))
        }
        return json(200, JSONObject()
            .put("object", "list")
            .put("items", items)
            .put("data", JSONObject().put("items", items)))
    }

    private fun legacyContent(parms: Map<String, String>): Response {
        val id = parms["id"]
        // 兼容 chapterId(规范/新调用方) 与 chapter(旧草稿)
        val chapter = parms["chapterId"] ?: parms["chapter"]
        if (id.isNullOrBlank() || chapter.isNullOrBlank()) return json(400, err("invalid_request", "缺参数 id/chapterId"))
        val index = resolveChapterIndex(id, chapter)
            ?: return json(404, err("not_found", "章节不存在"))
        val rd = BookController.getBookContent(
            mapOf("url" to listOf(id), "index" to listOf(index.toString())))
        if (!rd.isSuccess) return json(502, err("source_error", rd.errorMsg ?: "正文获取失败"))
        return json(200, JSONObject().put("object", "novel-content")
            .put("data", JSONObject().put("text", (rd.data as? String) ?: "")))
    }

    private fun discover(parms: Map<String, String>): Response {
        val type = parms["type"] ?: "novel"
        val wantSourceType = MODULES[type] ?: 0
        val sources = appDb.bookSourceDao.allEnabledExplore
            .filter { it.bookSourceType == wantSourceType && !it.exploreUrl.isNullOrBlank() }
        val items = JSONArray()
        // ★ 总预算兜底: 发现页要遍历**全部**带 exploreUrl 的源(实测 2060 条),
        //   旧实现只限单源 8s、不限总量 → 源多时单个请求可跑几分钟, 调用方必然超时。
        //   这里给总预算, 到点即返回**已收集到的部分结果**(发现页按源累加, 部分可用)。
        val deadline = System.currentTimeMillis() + DISCOVER_BUDGET_MS
        var truncated = false
        for (bs in sources) {
            if (System.currentTimeMillis() >= deadline) {
                truncated = true
                break
            }
            // exploreKinds 可能执行书源 JS, 单源限时 3s, 失败跳过不影响其他源
            val kinds = runBlocking {
                withTimeoutOrNull(3_000) { runCatching { bs.exploreKinds() }.getOrNull() }
            } ?: continue
            val tags = JSONArray()
            for (k in kinds) {
                val u = k.url ?: continue
                tags.put(JSONObject().put("name", k.title).put("url", u))
            }
            if (tags.length() == 0) continue
            items.put(JSONObject()
                .put("source", bs.bookSourceUrl)
                .put("sourceName", bs.bookSourceName)
                .put("tags", tags))
        }
        return json(200, JSONObject()
            .put("object", "list")
            .put("items", items)
            .put("truncated", truncated)
            .put("total", sources.size)
            .put("data", JSONObject().put("items", items)))
    }

    private fun explore(parms: Map<String, String>): Response {
        val source = parms["source"]
        val tagUrl = parms["url"]
        if (source.isNullOrBlank() || tagUrl.isNullOrBlank()) return json(400, err("invalid_request", "缺参数 source/url"))
        val page = parms["page"]?.toIntOrNull() ?: 1
        val bs = appDb.bookSourceDao.getBookSource(source)
            ?: return json(404, err("not_found", "书源不存在"))
        val books = runBlocking {
            withTimeoutOrNull(25_000) {
                runCatching { WebBook.exploreBookAwait(bs, tagUrl, page) }.getOrNull()
            }
        } ?: return json(502, err("source_error", "发现列表获取失败"))
        val items = JSONArray()
        for (sb in books) {
            val st = when {
                sb.type and BookType.video != 0 -> 4
                sb.type and BookType.image != 0 -> 2
                sb.type and BookType.audio != 0 -> 1
                else -> 0
            }
            val bookUrl = sb.bookUrl
            if (bookUrl.isBlank()) continue
            cachePut(bookUrl, mapOf(
                "name" to sb.name, "author" to (sb.author ?: ""),
                "kind" to (sb.kind ?: ""), "coverUrl" to (sb.coverUrl ?: ""),
                "intro" to (sb.intro ?: ""), "bookUrl" to bookUrl,
                "origin" to sb.origin, "originName" to sb.originName,
                "sourceType" to st
            ))
            items.put(JSONObject()
                .put("id", bookUrl)
                .put("name", sb.name)
                .put("author", sb.author ?: "")
                .put("coverUrl", sb.coverUrl ?: "")
                .put("intro", (sb.intro ?: "").take(200))
                .put("kind", sb.kind ?: ""))
        }
        return json(200, JSONObject()
            .put("object", "list")
            .put("items", items)
            .put("data", JSONObject().put("items", items)))
    }

    // ─────────────────────────── 公共支撑 ───────────────────────────

    private data class Loaded(val book: Book, val chapter: BookChapter, val raw: String)

    /** 目录: 书不在库时按搜索缓存补登, 再走官方 getChapterList */
    private fun loadToc(bookUrl: String): List<BookChapter>? {
        ensureBook(bookUrl)
        val rd = BookController.getChapterList(mapOf("url" to listOf(bookUrl)))
        if (!rd.isSuccess) return null
        @Suppress("UNCHECKED_CAST")
        return (rd.data as? List<*>)?.filterIsInstance<BookChapter>()
    }

    /** chapter 可能是序号也可能是 URL */
    private fun resolveChapterIndex(bookUrl: String, chapterRef: String): Int? {
        chapterRef.toIntOrNull()?.let { return it }
        loadToc(bookUrl) ?: return null
        return appDb.bookChapterDao.getChapterList(bookUrl).firstOrNull { it.url == chapterRef }?.index
    }

    /** 取章节原始内容(优先缓存, 未命中则联网解析) */
    private fun loadRaw(bookUrl: String, index: Int): Loaded? {
        val book = appDb.bookDao.getBook(bookUrl) ?: return null
        val chapter = appDb.bookChapterDao.getChapter(bookUrl, index) ?: return null
        val cached = runCatching { BookHelp.getContent(book, chapter) }.getOrNull()
        val raw = cached ?: run {
            val bs = appDb.bookSourceDao.getBookSource(book.origin) ?: return null
            runBlocking {
                runCatching { withTimeoutOrNull(25_000) { WebBook.getContentAwait(bs, book, chapter) } }
                    .getOrNull()
            } ?: return null
        }
        return Loaded(book, chapter, raw)
    }

    /** 书不在 Legado 库 → 用搜索/发现缓存构造 Book 入库(tocUrl 留空, refreshToc 会自动取详情) */
    private fun ensureBook(bookUrl: String) {
        if (appDb.bookDao.getBook(bookUrl) != null) return
        val c = searchCache[bookUrl] ?: return
        val bookType = when ((c["sourceType"] as? Int) ?: 0) {
            2 -> BookType.image
            1 -> BookType.audio
            4 -> BookType.video
            else -> BookType.text
        }
        runCatching {
            Book(
                bookUrl = bookUrl,
                origin = (c["origin"] as? String) ?: "",
                originName = (c["originName"] as? String) ?: "",
                name = (c["name"] as? String) ?: "",
                author = (c["author"] as? String) ?: "",
                kind = (c["kind"] as? String),
                coverUrl = (c["coverUrl"] as? String),
                intro = (c["intro"] as? String),
                type = bookType,
            ).savePreservingCustomCoverUrl()
        }
    }

    private fun cachePut(bookUrl: String, v: Map<String, Any?>) {
        searchCache[bookUrl] = v
        if (searchCache.size > 500) searchCache.remove(searchCache.keys.first())
    }

    /**
     * 结果项能否作为 THP 的「书 id」暴露出去。
     *
     * 必须挡住的实测样本（内置源包里的死站源）:
     *   id   = "https://www.sistxt.net/search/,{\n\t\"method\":\"POST\",\n\t\"body\":\"searchkey=测试\"\n\t}"
     *   name = "404 Not Found"
     * 这串 id 根本不是 URL，而是 Legado 的 POST 记法 searchUrl（"url,{json}"）+ 规则原文。
     * 它会经 THP 原样吐给前端：前端拿去请求必然失败，且把书源规则内容泄漏到了协议层。
     *
     * 产生路径有两处，均已修：
     *   1. WebBook.searchBookAwait 现在拦 4xx/5xx（错误页不再进入书单解析）；
     *   2. EngineSearchController 的 isUsableBookUrl 过滤 + 去重。
     * 这里再做一次，是因为 THP 是跨进程契约边界，不能假设上游一定干净。
     */
    private fun isUsableBookUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        for (c in url) {
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' ||
                c == '{' || c == '}' || c == '"' || c == '\'' || c == '\\'
            ) return false
        }
        return true
    }

    /**
     * 解析 POST 的 JSON body。
     *
     * ★ 不用 NanoHTTPD 的 `parseBody()`，因为它按 `Content-Type` 里声明的 charset 解码
     * （`ContentType.getEncoding()` 无 charset 时返回 **US-ASCII**，见 nanohttpd 2.3.1 字节码），
     * 而 JSON 按 RFC 8259 **恒为 UTF-8**。于是调用方只要发
     * `Content-Type: application/json`（不带 charset）—— 这正是 v2/v4 后端 `thp1.js` 的写法 ——
     * 多字节字符会被 US-ASCII 解码器逐一替换成 U+FFFD（**有损，不可逆**）。
     * 实测：中文搜索词「测试」到引擎后变成 "������"，即中文搜索整体失效。
     *
     * 这里改为自己按 Content-Length 读原始字节、强制 UTF-8 解码。
     * 读满 Content-Length 字节与原 `parseBody()` 的消费量一致，keep-alive 语义不变
     * （NanoHTTPD 的 `execute()` 只 skip 请求头，不会替我们排空 body）。
     */
    private fun readJsonBody(session: IHTTPSession): JSONObject? = runCatching {
        val len = session.headers?.get("content-length")?.trim()?.toIntOrNull() ?: 0
        if (len <= 0 || len > MAX_BODY_BYTES) return@runCatching null
        val buf = ByteArray(len)
        val ins = session.inputStream
        var off = 0
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        val text = String(buf, 0, off, Charsets.UTF_8).trim()
        if (text.isEmpty()) null else JSONObject(text)
    }.getOrNull()

    // ─────────────────────────── 响应封装 ───────────────────────────

    /**
     * THP §3.1: 成功信封**必须**含 meta（无分页信息时也要给 {}，不能省字段）。
     * §3.3 规定 meta.source = 产生数据的 peer 的 instanceId，故这里默认带上本引擎的 instanceId。
     */
    private fun okSpec(data: Any, meta: JSONObject? = null) = JSONObject()
        .put("ok", true)
        .put("data", data)
        .put("meta", meta ?: baseMeta())

    private fun baseMeta() = JSONObject().put("source", instanceId)

    /** 分页三件套(§8): cursor + hasMore + total。引擎单轮返回全量 → cursor 空串、hasMore=false */
    private fun pageMeta(total: Int) = baseMeta()
        .put("cursor", "")
        .put("hasMore", false)
        .put("total", total)

    private fun errSpec(code: String, msg: String) = JSONObject()
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", msg))
        .put("meta", baseMeta())

    /** 旧草稿信封 —— 只给 /thp/search|chapters|content|discover|explore 用, 勿在新端点使用 */
    private fun err(type: String, msg: String) = JSONObject()
        .put("object", "error")
        .put("data", JSONObject().put("type", type).put("message", msg))

    private fun json(code: Int, obj: JSONObject): Response {
        // NanoHTTPD 的 Status 枚举并不覆盖全部 4xx/5xx（例如没有 502），
        // firstOrNull 取不到就会静默退化成 200 —— 调用方若只看 HTTP 状态，会把错误当成功。
        // 因此按区间兜底：4xx → BAD_REQUEST，5xx → INTERNAL_ERROR。
        val status = Response.Status.values().firstOrNull { it.requestStatus == code }
            ?: when (code) {
                in 400..499 -> Response.Status.BAD_REQUEST
                in 500..599 -> Response.Status.INTERNAL_ERROR
                else -> Response.Status.OK
            }
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        // THP §3.1: 调用方生成 X-TH-Request-Id, peer 必须原样回显(日志追踪链路)
        requestId.get()?.let { r.addHeader("X-TH-Request-Id", it) }
        return r
    }

    private val REGEX_MODULE = Regex("^/thp/m/([A-Za-z0-9_]+)/([A-Za-z0-9_:]+)$")
}
