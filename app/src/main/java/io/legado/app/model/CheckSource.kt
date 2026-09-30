package io.legado.app.model

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.service.CheckSourceService
import io.legado.app.utils.startService
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

object CheckSource {
    internal const val EXTRA_SESSION_ID = "checkSourceSessionId"
    internal const val EXTRA_SELECTED_SOURCES_KEY = "checkSourceSelectedSourcesKey"

    var keyword = "我的"

    //校验设置
    // ★单源预算默认从 180s 收到 45s。
    //   这个 timeout 是**一个源的六个阶段（域名/搜索/发现/详情/目录/正文）共用**的预算，
    //   而上限决定的是"最坏要跑多久"：出厂源 3000+ 条 ÷ 64 线程 × 180s ≈ 2.9 小时，
    //   实际会因为重试、慢站拖得更久，最后不是跑完而是被系统回收掉 —— 表现为
    //   「跑了很久然后自己停了」。45s 对活源足够宽裕（六个请求的正常耗时是秒级），
    //   却把死站的尾巴砍掉 4 倍，让整轮体检能真正跑完。
    //   用户在「校验书源」设置里改过值就以用户值为准（CacheManager 里有值就不走默认）。
    var timeout = CacheManager.getLong("checkSourceTimeout") ?: 45000L
    var wSourceComment = CacheManager.get("wSourceComment")?.toBoolean() ?: true
    var checkDomain = CacheManager.get("checkDomain")?.toBoolean() ?: false
    var checkSearch = CacheManager.get("checkSearch")?.toBoolean() ?: true
    var checkDiscovery = CacheManager.get("checkDiscovery")?.toBoolean() ?: true
    var checkInfo = CacheManager.get("checkInfo")?.toBoolean() ?: true
    var checkCategory = CacheManager.get("checkCategory")?.toBoolean() ?: true
    var checkContent = CacheManager.get("checkContent")?.toBoolean() ?: true
    val summary get() = upSummary()

    suspend fun start(
        context: Context,
        sources: List<BookSourcePart>,
        sessionId: Long,
    ): String {
        Debug.prepareCheckSession(sessionId, sources.map { it.bookSourceUrl })
        var selectedSourcesKey: String? = null
        try {
            val queued = withContext(IO) { appDb.bookSourceDao.beginCheck(sources) }
            selectedSourcesKey = IntentData.put(queued)
            context.startService<CheckSourceService> {
                action = IntentAction.start
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SELECTED_SOURCES_KEY, selectedSourcesKey)
            }
        } catch (error: Exception) {
            selectedSourcesKey?.let { IntentData.get<Any>(it) }
            Debug.finishChecking(sessionId)
            throw error
        }
        return requireNotNull(selectedSourcesKey)
    }

    fun stop(context: Context, sessionId: Long) {
        context.startService<CheckSourceService> {
            action = IntentAction.stop
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
    }

    fun resume(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.resume
        }
    }

    fun putConfig() {
        CacheManager.put("checkSourceTimeout", timeout)
        CacheManager.put("wSourceComment", wSourceComment)
        CacheManager.put("checkDomain", checkDomain)
        CacheManager.put("checkSearch", checkSearch)
        CacheManager.put("checkDiscovery", checkDiscovery)
        CacheManager.put("checkInfo", checkInfo)
        CacheManager.put("checkCategory", checkCategory)
        CacheManager.put("checkContent", checkContent)
    }

    private fun upSummary(): String {
        var checkItem = ""
        if (checkDomain) checkItem = "$checkItem ${appCtx.getString(R.string.domain)}"
        if (checkSearch) checkItem = "$checkItem ${appCtx.getString(R.string.search)}"
        if (checkDiscovery) checkItem = "$checkItem ${appCtx.getString(R.string.discovery)}"
        if (checkInfo) checkItem = "$checkItem ${appCtx.getString(R.string.source_tab_info)}"
        if (checkCategory) checkItem = "$checkItem ${appCtx.getString(R.string.chapter_list)}"
        if (checkContent) checkItem = "$checkItem ${appCtx.getString(R.string.main_body)}"
        return appCtx.getString(
            R.string.check_source_config_summary,
            (timeout / 1000).toString(),
            checkItem
        )
    }
}
