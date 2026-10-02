package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSourcePart
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
     * 深搜（[sweep]）单源超时(ms)。★30s → 12s。
     *
     * 全源搜索的并发槽只有 64 个（AppConst.MAX_SEARCH_THREAD）。实测库里 3660 个启用源，
     * 8s 预算只跑完 45 个、25s 只跑完约 200 个 —— 瓶颈不是网速，而是**槽被死源占满**：
     * 一个「连得上却不响应」的源要独占一个槽直到 30s 超时，且超时的源不计入
     * onSearchProgress（协程已取消），于是 ThpServer.deepSweep 看到的 progressed 常年接近 0，
     * 深搜推进极慢、前端表现为「搜来搜去只有几本」。
     * 12s 仍远大于正常源的搜索耗时（健康源通常 0.5~3s），只掐掉真正的挂死源。
     */
    private const val SWEEP_SOURCE_TIMEOUT_MS = 12_000L

    /**
     * 上一轮 search() 的扫描统计：已扫完的源数 / 本轮参与的源总数。
     *
     * 存在的理由：`search()` 到时返回的是**预算内的部分结果**，但调用方从返回列表上
     * 看不出"是全网就这些，还是只扫了一半就被预算截断了"。ThpServer 需要这个信号来
     * 决定 `truncated` 并据此告诉前端「还能加载更多（可用更大的 budget 再扫深一点）」。
     * 单进程内被连续调用会互相覆盖，只用于"最近一轮"的展示，不参与正确性判断。
     */
    @Volatile
    var lastScannedSources: Int = 0
        private set

    @Volatile
    var lastTotalSources: Int = 0
        private set

    /** 结果是否被预算截断（还有源没扫完）。 */
    val lastTruncated: Boolean
        get() = lastTotalSources > 0 && lastScannedSources < lastTotalSources

    /** 一轮深搜的工作单元结果（见 [sweep]）。 */
    class SweepResult(
        /** 归一化后的结果项（与 search() 的返回项同形状）。 */
        val items: List<Map<String, Any?>>,
        /** 本轮跑完的源数（截断时 < totalSources，是「下一块」的断点依据）。 */
        val scannedSources: Int,
        val totalSources: Int,
        /** 本轮是否有任何源返回过条目 —— 为假说明源内这一页已见底，不必再翻下一页。 */
        val roundHasItems: Boolean,
        /** 本轮被时间预算截断（源子集里还有没跑完的）。 */
        val truncated: Boolean,
    )

    /**
     * 深搜一轮：只扫给定**源子集**在源内第 [page] 页上的结果。
     *
     * 这是「不断续搜」深度翻页的单轮工作单元 —— ThpServer.deepSweep 按块(chunk)反复调用：
     * 每轮换一块源、同一页码；全部源过完一页后 page+1 继续，直到所有源见底。
     * 与 [search] 的区别：search 是老 HTTP 接口的一次性全源扫描（行为保持不变）。
     *
     * @param timeoutSec 本轮时间预算(秒)。到时返回**已收集到的部分结果**（聚合搜索允许
     *   部分返回）而不是报错 —— 截断与否由 [SweepResult.truncated] 告知，调用方据此
     *   把断点记下来，下一轮从断点继续，永不回头重扫。
     */
    fun sweep(
        sources: List<BookSourcePart>,
        key: String,
        page: Int = 1,
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
    ): SweepResult {
        val total = sources.size
        lastScannedSources = 0
        lastTotalSources = total
        if (key.isEmpty() || sources.isEmpty()) {
            return SweepResult(emptyList(), 0, total, false, false)
        }
        // ★ 用「最新快照」而不是「追加」。
        // SearchModel 每次 onSearchSuccess 传的是**累计**列表（见 SearchModel.startSearch:
        // mergeItems(items,…) 之后再 callBack.onSearchSuccess(searchBooks)）。
        // 旧实现 results.addAll(searchBooks) 会把累计列表反复追加：
        // 第 i 次回调追加 i 条 → n 个源共 n²/2 条重复。实测 300 源即 48,215 条，
        // 既浪费内存/CPU，又让「取前 N 条」全是重复项（前端看到整页同一本书）。
        // 语义上每个快照都是自洽的完整结果，所以整体替换即可。
        val snapshot = AtomicReference<List<SearchBook>>(emptyList())
        val done = CountDownLatch(1)
        var finishHasMore = false
        val callBack = object : SearchModel.CallBack {
            override fun getSearchScope(): SearchScope = SearchScope(AppConfig.searchScope)
            override fun onSearchStart() {}
            override fun onSearchProgress(searched: Int, total: Int) {
                lastScannedSources = searched
                lastTotalSources = total
            }
            override fun onSearchSuccess(searchBooks: List<SearchBook>) {
                snapshot.set(ArrayList(searchBooks))
            }
            override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
                finishHasMore = hasMore
                done.countDown()
            }
            override fun onSearchCancel(exception: Throwable?) { done.countDown() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val model = SearchModel(scope, callBack)
            // ★ 只对深搜路径收紧单源超时（老 HTTP 接口 search() 走默认 30s，行为不变）。
            model.sourceTimeoutMs = SWEEP_SOURCE_TIMEOUT_MS
            model.search(System.currentTimeMillis(), key, sources, page)
            val finished = done.await(timeoutSec, TimeUnit.SECONDS)
            runCatching { model.close() }
            val books = snapshot.get()
            SweepResult(
                items = normalize(books),
                // 本轮是否「有货」：快照非空 或 正常收尾时 SearchModel 报过命中。
                // 超时截断时 onSearchFinish 不会触发，只能看快照 —— 两个信号取或。
                scannedSources = lastScannedSources,
                totalSources = total,
                roundHasItems = books.isNotEmpty() || finishHasMore,
                truncated = !finished || lastScannedSources < total,
            )
        } catch (e: Exception) {
            SweepResult(emptyList(), lastScannedSources, total, false, true)
        } finally {
            scope.cancel()
        }
    }

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
        return try {
            val sources = SearchScope(AppConfig.searchScope).getBookSourceParts()
            ReturnData().setData(sweep(sources, key, 1, timeoutSec).items)
        } catch (e: Exception) {
            ReturnData().setErrorMsg("搜索失败: ${e.message}")
        }
    }

    /** 把一轮的 SearchBook 快照归一化成出货形状（去重 + 剔脏 + 附 sourceType）。 */
    private fun normalize(books: List<SearchBook>): List<Map<String, Any?>> {
        // 附带书源类型, 方便后端按模块路由(0小说 1音频 2漫画 4视频)
        // 注意: SearchBook.type 是 BookType 位标志(video=4/text=8/audio=32/image=64), 需归一化为 0/1/2/4
        // 视频位最具体(旧版 legado 曾用 4 表示视频), 优先判定, 否则视频源会被归成 0 小说 → 视频模块永远空
        val seen = HashSet<String>()
        val list = ArrayList<Map<String, Any?>>()
        for (b in books) {
            // ★ 丢弃不可用的结果项（见 isUsableBookUrl 注释）
            val url = b.bookUrl
            if (!isUsableBookUrl(url)) continue
            if (!seen.add(url)) continue   // 同书多源/多轮回调产生的重复项
            if (b.name.isNullOrBlank()) continue
            // ★ 丢弃「HTTP 错误页被当书」的脏结果（见 isErrorPageName）
            if (isErrorPageName(b.name)) continue
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
        return list
    }

    /**
     * 结果项是否可用作「书 id」。
     *
     * ★ Legado 的 POST 源把请求方式写在 searchUrl 里，整串形如
     *     `https://site/search/,{"method":"POST","body":"searchkey=xx"}`
     * 这是**合法且必需**的 —— 取目录/正文时 Legado 靠结尾这段 JSON 才知道要发 POST。
     * 因此只校验逗号前的 **URL 部分**，不能因为整串里出现 `{` / `"` 就丢弃：
     * 上一版正是那样写的，会把所有 POST 源的搜索结果**整片误杀**（实测死站源
     * `https://www.sistxt.net/search/,{…}` 的全部条目被丢光，等于把「有一堆结果」
     * 变成「一条都没有」，比不修更糟）。
     *
     * 真正要挡的是「HTTP 错误页被当成书」这类脏结果。实测样本：
     *   id   = "https://www.sistxt.net/search/,{…}"   ← URL 部分是合法的，拦不住
     *   name = "404 Not Found"                        ← 破绽在 name，交给 [isErrorPageName]
     * 其产生路径见 WebBook.searchBookAwait（现已拦 4xx/5xx，从源头不再产出）。
     */
    private fun isUsableBookUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val head = url.substringBefore(",{").trim()
        if (head.isEmpty()) return false
        if (!head.startsWith("http://", true) && !head.startsWith("https://", true)) return false
        // URL 部分不该出现空白（未展开的模板 / 被拼接进来的规则片段）
        for (c in head) {
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') return false
        }
        return true
    }

    /**
     * 书名是否是 HTTP 错误页标题。**精确匹配**为主，避免误伤正常书名。
     * 兜住 WebBook 那道闸漏下来的（例如源站返回 200 但内容是错误页）。
     */
    private val ERROR_PAGE_TITLES = setOf(
        "404 not found", "403 forbidden", "401 unauthorized", "400 bad request",
        "500 internal server error", "502 bad gateway", "503 service unavailable",
        "504 gateway time-out", "504 gateway timeout",
        "just a moment...", "attention required! | cloudflare",
        "access denied", "not found", "page not found",
        "页面不存在", "出错啦", "错误",
    )

    private fun isErrorPageName(name: String?): Boolean {
        val n = name?.trim()?.lowercase() ?: return false
        if (n.isEmpty()) return false
        if (ERROR_PAGE_TITLES.contains(n)) return true
        // "404 Not Found - 站点名" / "403 Forbidden | xxx" 这类带后缀的
        return n.startsWith("404 ") || n.startsWith("403 ") ||
            n.startsWith("502 ") || n.startsWith("503 ")
    }
}
