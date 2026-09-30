package io.legado.app.ui.book.search

import androidx.lifecycle.MutableLiveData
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourceCheckState
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.engine.EngineProfile
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.splitNotBlank
import splitties.init.appCtx

/**
 * 搜索范围
 */
@Suppress("unused")
data class SearchScope(private var scope: String) {

    constructor(groups: List<String>) : this(groups.joinToString(","))

    constructor(source: BookSource) : this(
        "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
    )

    constructor(source: BookSourcePart) : this(
        "${source.bookSourceName.replace(":", "")}::${source.bookSourceUrl}"
    )

    override fun toString(): String {
        return scope
    }

    val stateLiveData = MutableLiveData(scope)

    fun update(scope: String, postValue: Boolean = true, save: Boolean = true) {
        this.scope = scope
        if (postValue) stateLiveData.postValue(scope)
        if (save) { //不对单书源的搜索进行缓存，防止下次依旧为单书源搜索（单书源搜索需要每次都指定）
            save()
        }
    }

    fun update(groups: List<String>) {
        scope = groups.joinToString(",")
        stateLiveData.postValue(scope)
        save()
    }

    fun update(source: BookSource) {
        scope = "${source.bookSourceName}::${source.bookSourceUrl}"
        stateLiveData.postValue(scope)
        if (!isSource()) {
            save()
        }
    }

    fun isSource(): Boolean {
        return scope.contains("::")
    }

    val display: String
        get() {
            if (scope.contains("::")) {
                return scope.substringBefore("::")
            }
            if (scope.isEmpty()) {
                return appCtx.getString(R.string.all_source)
            }
            return scope
        }

    /**
     * 搜索范围显示
     */
    val displayNames: List<String>
        get() {
            val list = arrayListOf<String>()
            if (scope.contains("::")) {
                list.add(scope.substringBefore("::"))
            } else {
                scope.splitNotBlank(",").forEach {
                    list.add(it)
                }
            }
            return list
        }

    fun remove(scope: String) {
        if (isSource()) {
            this.scope = ""
        } else {
            val stringBuilder = StringBuilder()
            this.scope.split(",").forEach {
                if (it != scope) {
                    if (stringBuilder.isNotEmpty()) {
                        stringBuilder.append(",")
                    }
                    stringBuilder.append(it)
                }
            }
            this.scope = stringBuilder.toString()
        }
        stateLiveData.postValue(this.scope)
    }

    /**
     * 搜索范围书源
     */
    fun getBookSourceParts(): List<BookSourcePart> {
        val list = hashSetOf<BookSourcePart>()
        if (scope.isEmpty()) {
            list.addAll(appDb.bookSourceDao.allEnabledPart)
        } else {
            if (scope.contains("::")) {
                scope.substringAfter("::").let {
                    appDb.bookSourceDao.getBookSourcePart(it)?.let { source ->
                        list.add(source)
                    }
                }
            } else {
                val oldScope = scope.splitNotBlank(",")
                val newScope = oldScope.filter {
                    val bookSources = appDb.bookSourceDao.getEnabledPartByGroup(it)
                    list.addAll(bookSources)
                    bookSources.isNotEmpty()
                }
                if (oldScope.size != newScope.size) {
                    update(newScope)
                    stateLiveData.postValue(scope)
                }
            }
            if (list.isEmpty()) {
                scope = ""
                appDb.bookSourceDao.allEnabledPart.let {
                    if (it.isNotEmpty()) {
                        stateLiveData.postValue(scope)
                        list.addAll(it)
                    }
                }
            }
        }
        // ★ 五产物化：按产物声明的源类型白名单过滤（见 EngineProfile）。
        //  「小说引擎」不该被拖去扫图片源、「漫画引擎」也不该扫文本源：
        //   广播 caps 只挡住了 THP 调用方，App 内的搜索/发现走的是这条路径。
        //   四合一（app）的白名单为空集合 → 不过滤，行为与旧版完全一致。
        // ★ 先按健康度、再按响应速度、最后按自定义顺序排。
        //
        // 为什么不能只按 customOrder：搜索有**固定时间预算**（THP 默认 20s，App 内 25s），
        // 而出厂源有 3000+ 条 —— 一轮扫描几乎必然扫不完，**没轮到的源就等于不存在**。
        // 而 SearchModel 是按本列表顺序并发取源的：前几十个源一旦都是「连得上但不响应」
        // 的死站，64 个并发槽会在单源 30s 超时里被整段占满，于是"源越多、结果越少"。
        // 实测过出厂包里有 385 条 customOrder 是 int32 溢出产生的负值（-2085978349 之类），
        // 它们会**排到最前面**优先占用预算，纯属浪费。
        //
        // 档位依据是「校验书源」写下的 checkStatus（PASSED / NEEDS_CHECK / FAILED）。
        // 没体检过时全部落在同一档、respondTime 又都等于默认值 → 退化为原来的
        // customOrder 顺序，保证「未体检 = 行为零变化」。
        // 用 thenBy 链而不是 compareBy 的多 selector 重载：后者要在一组返回类型
        // 不同的 lambda 之间做类型推断（Int / Long），显式类型参数的写法更稳。
        return list
            .filter { EngineProfile.allowsSourceType(it.bookSourceType) }
            .sortedWith(
                compareBy<BookSourcePart> { healthRank(it) }
                    .thenBy { respondKey(it) }
                    .thenBy { orderKey(it) }
            )
    }

    /**
     * 健康度档位（越小越先扫）。
     * PASSED 最先——刚验过是活的；FAILED 垫底——验过是死的。
     * ★ FAILED 只降权、**不剔除**：源站会复活，剔除等于永久损失。
     */
    private fun healthRank(part: BookSourcePart): Int = when (part.checkStatus) {
        BookSourceCheckState.PASSED -> 0
        BookSourceCheckState.FAILED -> 2
        else -> 1
    }

    /**
     * 排序用的响应时间。`respondTime` 的 0 / 负数 与默认哨兵 180000 都表示
     * "没有实测数据"，统一折成最大值排到有数据的后面 —— 否则那些没测过的源
     * 会因为字面上的 0 而抢到队首，正好与意图相反。
     */
    private fun respondKey(part: BookSourcePart): Long {
        val rt = part.respondTime
        return if (rt <= 0L || rt >= DEFAULT_RESPOND_TIME) Long.MAX_VALUE else rt
    }

    /**
     * 排序用的自定义序号。负值是导入/导出链路里 int32 溢出产生的垃圾
     * （实测有 -2085978349 这种），一律折到末尾，不让它们抢队首。
     */
    private fun orderKey(part: BookSourcePart): Long {
        val co = part.customOrder
        return if (co < 0) Long.MAX_VALUE else co.toLong()
    }

    private companion object {
        /** BookSource.respondTime 的默认值 = 「没测过」的哨兵。 */
        const val DEFAULT_RESPOND_TIME = 180_000L
    }

    fun isAll(): Boolean {
        return scope.isEmpty()
    }

    fun save() {
        AppConfig.searchScope = scope
        if (isAll() || isSource() || scope.contains(",")) {
            AppConfig.searchGroup = ""
        } else {
            AppConfig.searchGroup = scope
        }
    }

}
