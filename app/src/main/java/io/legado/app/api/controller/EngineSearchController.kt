package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.constant.BookType
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 引擎扩展: 把 WebSocket 搜索包装成普通 HTTP GET
 * 调用方无需 ws 客户端, 直接 GET /searchBookHttp?key=xxx 即可调用本引擎
 * 返回同时携带 sourceType, 调用方据此归一化输出(小说text/漫画images/音频audio)
 */
object EngineSearchController {

    /** 全源搜索的默认时间预算(秒)。旧 HTTP 接口/兼容端点沿用此值，保持既有行为不变。 */
    const val DEFAULT_TIMEOUT_SEC = 25L

    /**
     * @param timeoutSec 搜索时间预算(秒)。到时返回**已收集到的部分结果**（聚合搜索允许部分返回），
     *   而不是报错 —— THP §4 明确「你的引擎慢了只会被跳过」，返回部分结果远好于整体超时。
     *   THP 规范端点按调用方 8s 预算传入更小的值(见 ThpServer.SPEC_SEARCH_TIMEOUT_SEC)。
     */
    fun search(
        parameters: Map<String, List<String>>,
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
    ): ReturnData {
        val key = parameters["key"]?.firstOrNull()?.trim()
        if (key.isNullOrEmpty()) return ReturnData().setErrorMsg("参数key不能为空")
        // ★ 用「最新快照」而不是「追加」。
        // SearchModel 每次 onSearchSuccess 传的是**累计**列表（见 SearchModel.startSearch:
        // mergeItems(items,…) 之后再 callBack.onSearchSuccess(searchBooks)）。
        // 旧实现 results.addAll(searchBooks) 会把累计列表反复追加：
        // 第 i 次回调追加 i 条 → n 个源共 n²/2 条重复。实测 300 源即 48,215 条，
        // 既浪费内存/CPU，又让「取前 N 条」全是重复项（前端看到整页同一本书）。
        // 语义上每个快照都是自洽的完整结果，所以整体替换即可。
        val snapshot = AtomicReference<List<SearchBook>>(emptyList())
        val done = CountDownLatch(1)
        val callBack = object : SearchModel.CallBack {
            override fun getSearchScope(): SearchScope = SearchScope(AppConfig.searchScope)
            override fun onSearchStart() {}
            override fun onSearchProgress(searched: Int, total: Int) {}
            override fun onSearchSuccess(searchBooks: List<SearchBook>) {
                snapshot.set(ArrayList(searchBooks))
            }
            override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) { done.countDown() }
            override fun onSearchCancel(exception: Throwable?) { done.countDown() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val model = SearchModel(scope, callBack)
            model.search(System.currentTimeMillis(), key)
            done.await(timeoutSec, TimeUnit.SECONDS)
            runCatching { model.close() }
            // 附带书源类型, 方便后端按模块路由(0小说 1音频 2漫画 4视频)
            // 注意: SearchBook.type 是 BookType 位标志(video=4/text=8/audio=32/image=64), 需归一化为 0/1/2/4
            // 视频位最具体(旧版 legado 曾用 4 表示视频), 优先判定, 否则视频源会被归成 0 小说 → 视频模块永远空
            val seen = HashSet<String>()
            val list = ArrayList<Map<String, Any?>>()
            for (b in snapshot.get()) {
                // ★ 丢弃不可用的结果项（见 isUsableBookUrl 注释）
                val url = b.bookUrl
                if (!isUsableBookUrl(url)) continue
                if (!seen.add(url)) continue   // 同书多源/多轮回调产生的重复项
                if (b.name.isNullOrBlank()) continue
                val st = when {
                    b.type and BookType.video != 0 -> 4
                    b.type and BookType.image != 0 -> 2
                    b.type and BookType.audio != 0 -> 1
                    else -> 0
                }
                list.add(mapOf(
                    "name" to b.name, "author" to (b.author ?: ""),
                    "kind" to (b.kind ?: ""), "coverUrl" to (b.coverUrl ?: ""),
                    "intro" to (b.intro ?: ""), "bookUrl" to url,
                    "origin" to b.origin, "originName" to b.originName,
                    "sourceType" to st,
                    "typeName" to when (st) {
                        0 -> "text"; 1 -> "audio"; 2 -> "image"; 4 -> "video"; else -> "unknown"
                    }
                ))
            }
            ReturnData().setData(list)
        } catch (e: Exception) {
            ReturnData().setErrorMsg("搜索失败: ${e.message}")
        } finally {
            scope.cancel()
        }
    }

    /**
     * 结果项是否可用作「书 id」。
     *
     * 必须挡住的实测样本（来自内置源包里的死站源）:
     *   id  = "https://www.sistxt.net/search/,{\n\t\"method\":\"POST\",\n\t\"body\":\"searchkey=测试\"\n\t}"
     *   name= "404 Not Found"
     * 这串 id 不是 URL，而是 Legado 的 POST 记法 URL + 规则 JSON 原文，
     * 经 THP 端点原样吐给前端后：点不开（不是合法 URL）、且把书源规则内容泄漏到协议层。
     * 产生路径见 WebBook.searchBookAwait（现已拦 4xx/5xx）与 BookList 的「按详情页解析」兜底。
     * 这里做协议边界的最后一道闸：不是干净 URL 的一律不进结果集。
     */
    private fun isUsableBookUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        // URL 里不该出现这些字符（未展开的模板/规则原文/换行）
        for (c in url) {
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' ||
                c == '{' || c == '}' || c == '"' || c == '\'' || c == '\\'
            ) return false
        }
        return true
    }
}
