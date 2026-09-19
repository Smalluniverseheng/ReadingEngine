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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val results = CopyOnWriteArrayList<SearchBook>()
        val done = CountDownLatch(1)
        val callBack = object : SearchModel.CallBack {
            override fun getSearchScope(): SearchScope = SearchScope(AppConfig.searchScope)
            override fun onSearchStart() {}
            override fun onSearchProgress(searched: Int, total: Int) {}
            override fun onSearchSuccess(searchBooks: List<SearchBook>) { results.addAll(searchBooks) }
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
            val list = results.map { b ->
                val st = when {
                    b.type and BookType.video != 0 -> 4
                    b.type and BookType.image != 0 -> 2
                    b.type and BookType.audio != 0 -> 1
                    else -> 0
                }
                mapOf(
                    "name" to b.name, "author" to (b.author ?: ""),
                    "kind" to (b.kind ?: ""), "coverUrl" to (b.coverUrl ?: ""),
                    "intro" to (b.intro ?: ""), "bookUrl" to b.bookUrl,
                    "origin" to b.origin, "originName" to b.originName,
                    "sourceType" to st,
                    "typeName" to when (st) {
                        0 -> "text"; 1 -> "audio"; 2 -> "image"; 4 -> "video"; else -> "unknown"
                    }
                )
            }
            ReturnData().setData(list)
        } catch (e: Exception) {
            ReturnData().setErrorMsg("搜索失败: ${e.message}")
        } finally {
            scope.cancel()
        }
    }
}
