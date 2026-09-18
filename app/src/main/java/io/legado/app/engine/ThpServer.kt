package io.legado.app.engine

import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.EngineSearchController
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地阅读引擎服务端: 供局域网内的阅读前端按统一协议直接调用本引擎
 * 端口 1234(与 UDP HELLO 广播一致), 仅在局域网内监听, 无账号鉴权
 *
 * 端点(THP v1):
 *   GET /thp/meta                        → 引擎名片(name/caps/version/auth)
 *   GET /thp/search?type=novel&q=关键词   → {data:{items:[{id,name,author,coverUrl,intro,kind}]}}
 *   GET /thp/chapters?type=novel&id=书URL → {data:{items:[{name,url,index}]}}
 *   GET /thp/content?type=novel&id=书URL&chapter=章节URL → {data:{text}}
 *   GET /thp/discover?type=novel         → {data:{items:[{source,sourceName,tags:[{name,url}]}]}}
 *   GET /thp/explore?type=novel&source=源URL&url=分类URL&page=1 → 同 search 的书籍条目
 * 说明: 搜索/目录/正文/发现全部委托 Legado 本体(EngineSearchController/BookController/WebBook),
 *       规则解析 100% 由 Legado 引擎本体完成, 本层只做协议转换。
 */
class ThpServer(port: Int = 1234) : NanoHTTPD(port) {

    companion object {
        const val PORT = 1234
        const val VERSION = "engine-1.3.0"
        private var instance: ThpServer? = null

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

    // 搜索结果缓存: bookUrl → 搜索结果字段(目录阶段需要 origin 等信息补入库)
    private val searchCache = ConcurrentHashMap<String, Map<String, Any?>>()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (!uri.startsWith("/thp/")) return json(404, err("not_found", "未知端点"))
        return try {
            when {
                uri == "/thp/meta" -> json(200, JSONObject()
                    .put("object", "meta")
                    .put("data", JSONObject()
                        .put("name", "阅读引擎")
                        .put("version", VERSION)
                        .put("caps", JSONArray().put("novel").put("comic").put("audio"))
                        .put("auth", JSONArray().put("none"))))
                uri == "/thp/search" -> search(session.parms)
                uri == "/thp/chapters" -> chapters(session.parms)
                uri == "/thp/content" -> content(session.parms)
                // NanoHTTPD parms 是单值 Map, 控制器要 List<String> — 在各 handler 里转
                uri == "/thp/discover" -> discover(session.parms)
                uri == "/thp/explore" -> explore(session.parms)
                else -> json(404, err("not_found", "未知端点"))
            }
        } catch (e: Exception) {
            json(500, err("engine_error", e.message ?: "引擎内部错误"))
        }
    }

    // ── 搜索: 复用 EngineSearchController(官方 SearchModel 全量书源并发) ──
    private fun search(parms: Map<String, String>): Response {
        val q = parms["q"]?.trim()
        val type = parms["type"] ?: "novel"
        if (q.isNullOrEmpty()) return json(400, err("invalid_request", "缺参数 q"))
        // THP type → legado sourceType: novel→0(text) comic→2(image) music→1(audio)
        val wantType = when (type) { "comic" -> 2; "music" -> 1; else -> 0 }
        val rd = EngineSearchController.search(mapOf("key" to listOf(q)))
        if (!rd.isSuccess) return json(502, err("source_error", rd.errorMsg ?: "搜索失败"))
        @Suppress("UNCHECKED_CAST")
        val raw = (rd.data as? List<Map<String, Any?>>) ?: emptyList()
        val items = JSONArray()
        for (b in raw) {
            val st = (b["sourceType"] as? Int) ?: 0
            if (st != wantType) continue
            val bookUrl = (b["bookUrl"] as? String) ?: continue
            searchCache[bookUrl] = b
            if (searchCache.size > 500) searchCache.remove(searchCache.keys.first())
            items.put(JSONObject()
                .put("id", bookUrl)
                .put("name", b["name"] ?: "")
                .put("author", b["author"] ?: "")
                .put("coverUrl", b["coverUrl"] ?: "")
                .put("intro", (b["intro"] as? String ?: "").take(200))
                .put("kind", b["kind"] ?: ""))
        }
        return json(200, JSONObject().put("object", "list").put("data", JSONObject().put("items", items)))
    }

    // ── 目录: 书不在库时按搜索缓存补登, 再走官方 refreshToc ──
    private fun chapters(parms: Map<String, String>): Response {
        val id = parms["id"]
        if (id.isNullOrBlank()) return json(400, err("invalid_request", "缺参数 id"))
        val bookUrl = id
        ensureBook(bookUrl)
        val rd = BookController.getChapterList(mapOf("url" to listOf(bookUrl)))
        if (!rd.isSuccess) return json(502, err("source_error", rd.errorMsg ?: "目录获取失败"))
        val list = (rd.data as? List<*>) ?: emptyList<Any>()
        val items = JSONArray()
        for (c in list) {
            if (c !is BookChapter) continue
            items.put(JSONObject()
                .put("name", c.title)
                .put("url", c.url)
                .put("index", c.index))
        }
        return json(200, JSONObject().put("object", "list").put("data", JSONObject().put("items", items)))
    }

    // ── 正文: 章节URL → 反查 index → 官方 getBookContent ──
    private fun content(parms: Map<String, String>): Response {
        val id = parms["id"]
        val chapter = parms["chapter"]
        if (id.isNullOrBlank() || chapter.isNullOrBlank()) return json(400, err("invalid_request", "缺参数 id/chapter"))
        val bookUrl = id
        ensureBook(bookUrl)
        // chapter 可能是序号也可能是 URL
        var index = chapter.toIntOrNull()
        if (index == null) {
            val toc = appDb.bookChapterDao.getChapterList(bookUrl)
            val hit = toc.firstOrNull { it.url == chapter }
            index = hit?.index
        }
        if (index == null) return json(404, err("not_found", "章节不存在"))
        val rd = BookController.getBookContent(mapOf("url" to listOf(bookUrl), "index" to listOf(index.toString())))
        if (!rd.isSuccess) return json(502, err("source_error", rd.errorMsg ?: "正文获取失败"))
        return json(200, JSONObject().put("object", "novel-content")
            .put("data", JSONObject().put("text", (rd.data as? String) ?: "")))
    }

    // ── 发现: 返回各书源的分类标签(源 → tags), 前端按源分组渲染 ──
    private fun discover(parms: Map<String, String>): Response {
        val type = parms["type"] ?: "novel"
        // THP type → legado bookSourceType: novel→0(text) music→1(audio) comic→2(image) video→4
        val wantSourceType = when (type) { "music" -> 1; "comic" -> 2; "video" -> 4; else -> 0 }
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
        return json(200, JSONObject().put("object", "list").put("data", JSONObject().put("items", items)))
    }

    // ── 发现列表: 按 源+分类URL+页码 取书籍条目(字段与 search 对齐, 同样进缓存供目录/正文) ──
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
                sb.type and BookType.image != 0 -> 2
                sb.type and BookType.audio != 0 -> 1
                else -> 0
            }
            val bookUrl = sb.bookUrl
            if (bookUrl.isBlank()) continue
            val cached = mapOf(
                "name" to sb.name, "author" to (sb.author ?: ""),
                "kind" to (sb.kind ?: ""), "coverUrl" to (sb.coverUrl ?: ""),
                "intro" to (sb.intro ?: ""), "bookUrl" to bookUrl,
                "origin" to sb.origin, "originName" to sb.originName,
                "sourceType" to st
            )
            searchCache[bookUrl] = cached
            if (searchCache.size > 500) searchCache.remove(searchCache.keys.first())
            items.put(JSONObject()
                .put("id", bookUrl)
                .put("name", sb.name)
                .put("author", sb.author ?: "")
                .put("coverUrl", sb.coverUrl ?: "")
                .put("intro", (sb.intro ?: "").take(200))
                .put("kind", sb.kind ?: ""))
        }
        return json(200, JSONObject().put("object", "list").put("data", JSONObject().put("items", items)))
    }

    // 书不在 Legado 库 → 用搜索/发现缓存构造 Book 入库(tocUrl 留空, refreshToc 会自动取详情)
    private fun ensureBook(bookUrl: String) {
        if (appDb.bookDao.getBook(bookUrl) != null) return
        val c = searchCache[bookUrl] ?: return
        // 缓存里 sourceType 是归一化的 0/1/2, Book.type 需要 BookType 位标志
        val bookType = when ((c["sourceType"] as? Int) ?: 0) {
            2 -> BookType.image
            1 -> BookType.audio
            else -> BookType.text
        }
        val book = Book(
            bookUrl = bookUrl,
            origin = (c["origin"] as? String) ?: "",
            originName = (c["originName"] as? String) ?: "",
            name = (c["name"] as? String) ?: "",
            author = (c["author"] as? String) ?: "",
            kind = (c["kind"] as? String),
            coverUrl = (c["coverUrl"] as? String),
            intro = (c["intro"] as? String),
            type = bookType,
        )
        runCatching { book.save() }
    }

    private fun json(code: Int, obj: JSONObject): Response {
        val status = Response.Status.values().firstOrNull { it.requestStatus == code } ?: Response.Status.OK
        val r = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun err(type: String, msg: String) = JSONObject()
        .put("object", "error")
        .put("data", JSONObject().put("type", type).put("message", msg))
}
