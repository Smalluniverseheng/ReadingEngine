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
        const val VERSION = "1.5.0"
        const val PROTOCOL = "THP/1.0"
        private var instance: ThpServer? = null

        /** 模块名 → legado 书源类型(THP §6.1) */
        private val MODULES = mapOf(
            "novel" to 0,   // text
            "music" to 1,   // audio
            "comic" to 2,   // image
            "video" to 4,   // video
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

    private val instanceId: String by lazy { EngineBeacon.instanceId() }

    // ─────────────────────────── 路由 ───────────────────────────

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: return json(400, err("invalid_request", "缺 uri"))
        if (!uri.startsWith("/thp")) return json(404, err("not_found", "未知端点"))

        // 取参: POST 走 JSON body, 否则走 query
        val q = session.parms ?: emptyMap()
        val body: JSONObject? = if (session.method == Method.POST) readJsonBody(session) else null
        val get: (String) -> String? = { k -> body?.optString(k)?.takeIf { it.isNotEmpty() } ?: q[k] }

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

                else -> json(404, err("not_found", "未知端点"))
            }
        } catch (e: Exception) {
            json(500, err("engine_error", e.message ?: "引擎内部错误"))
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
        .put("caps", JSONArray().put("m:novel").put("m:comic").put("m:music").put("m:video"))
        .put("auth", JSONArray().put("none"))
        .put("remote", false)
        .put("endpoints", JSONArray().put("search").put("toc").put("content"))
        .put("deprecated", JSONArray())
        .put("ext", JSONObject())))

    private fun specSearch(module: String, get: (String) -> String?): Response {
        val q = (get("q") ?: get("key"))?.trim()
        if (q.isNullOrEmpty()) return json(400, errSpec("INVALID_REQUEST", "缺参数 q"))
        val limit = get("limit")?.toIntOrNull() ?: 50
        val rd = EngineSearchController.search(mapOf("key" to listOf(q)))
        if (!rd.isSuccess) return json(502, errSpec("UPSTREAM_ERROR", rd.errorMsg ?: "搜索失败"))
        @Suppress("UNCHECKED_CAST")
        val raw = (rd.data as? List<Map<String, Any?>>) ?: emptyList()
        val arr = JSONArray()
        var n = 0
        for (b in raw) {
            if (n >= limit) break
            val st = (b["sourceType"] as? Int) ?: 0
            if (st != MODULES[module]) continue
            val bookUrl = (b["bookUrl"] as? String) ?: continue
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
        return json(200, okSpec(arr))
    }

    private fun specToc(module: String, get: (String) -> String?): Response {
        val id = get("id")
        if (id.isNullOrBlank()) return json(400, errSpec("INVALID_REQUEST", "缺参数 id"))
        val list = loadToc(id) ?: return json(502, errSpec("UPSTREAM_ERROR", "目录获取失败"))
        val arr = JSONArray()
        for (c in list) {
            arr.put(JSONObject()
                .put("id", c.url)
                .put("name", c.title)
                .put("index", c.index))
        }
        return json(200, okSpec(arr))
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
                if (!rd.isSuccess) json(502, errSpec("UPSTREAM_ERROR", rd.errorMsg ?: "正文获取失败"))
                else json(200, okSpec(JSONObject().put("text", (rd.data as? String) ?: "")))
            }
            "comic" -> {
                val loaded = loadRaw(id, index)
                    ?: return json(502, errSpec("UPSTREAM_ERROR", "图片列表获取失败"))
                val images = runBlocking {
                    runCatching {
                        withTimeoutOrNull(20_000) {
                            BookHelp.flowImages(loaded.chapter, loaded.raw).toList()
                        }
                    }.getOrNull()
                } ?: return json(502, errSpec("UPSTREAM_ERROR", "图片规则解析失败"))
                json(200, okSpec(JSONObject().put("images", JSONArray(images))))
            }
            "music", "video" -> {
                val loaded = loadRaw(id, index)
                    ?: return json(502, errSpec("UPSTREAM_ERROR", "播放地址获取失败"))
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
                    ?: return json(502, errSpec("UPSTREAM_ERROR", "播放地址解析失败"))
                val header = JSONObject()
                analyzed?.headerMap?.forEach { (k, v) -> header.put(k, v) }
                json(200, okSpec(JSONObject()
                    .put("url", resolved)
                    .put("header", header)
                    .put("variants", JSONArray())))
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
        for (b in raw) {
            val st = (b["sourceType"] as? Int) ?: 0
            if (st != wantType) continue
            val bookUrl = (b["bookUrl"] as? String) ?: continue
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
        for (bs in sources) {
            // exploreKinds 可能执行书源 JS, 单源限时 8s, 失败跳过不影响其他源
            val kinds = runBlocking {
                withTimeoutOrNull(8_000) { runCatching { bs.exploreKinds() }.getOrNull() }
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

    private fun readJsonBody(session: IHTTPSession): JSONObject? = runCatching {
        // NanoHTTPD: parseBody 会把 POST 正文放进 files["postData"]
        val files = HashMap<String, String>()
        session.parseBody(files)
        JSONObject(files["postData"]?.takeIf { it.isNotBlank() } ?: "{}")
    }.getOrNull()

    // ─────────────────────────── 响应封装 ───────────────────────────

    private fun okSpec(data: Any) = JSONObject().put("ok", true).put("data", data)

    private fun errSpec(code: String, msg: String) = JSONObject()
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", msg))

    private fun err(type: String, msg: String) = JSONObject()
        .put("object", "error")
        .put("data", JSONObject().put("type", type).put("message", msg))

    private fun json(code: Int, obj: JSONObject): Response {
        val status = Response.Status.values().firstOrNull { it.requestStatus == code } ?: Response.Status.OK
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private val REGEX_MODULE = Regex("^/thp/m/([A-Za-z0-9_]+)/([A-Za-z0-9_:]+)$")
}
