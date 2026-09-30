package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.engine.EngineProfile
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.clearSharedGlobalState
import io.legado.app.model.BookCover
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.File

object DefaultData {

    fun upVersion() {
        if (LocalConfig.versionCode < AppConst.appInfo.versionCode) {
            Coroutine.async {
                if (LocalConfig.needUpHttpTTS) {
                    importDefaultHttpTTS()
                }
                if (LocalConfig.needUpTxtTocRule) {
                    importDefaultTocRules()
                }
                if (LocalConfig.needUpRssSources) {
                    importDefaultRssSources()
                }
                if (LocalConfig.needUpDictRule) {
                    importDefaultDictRules()
                }
                // ★ 顺序不能反：`EngineProfile.activated` 必须写在前面。
                //    `needUpDefaultBookSource` 是**读到即写版本号**的属性（isLastVersion），
                //    先读它就等于把「已导入」标记写死；未激活的那台机器随后激活也不会再导入，
                //    表现为「输了密码但还是一个源都没有」。Kotlin 的 && 短路保护了这一点。
                if (EngineProfile.activated && LocalConfig.needUpDefaultBookSource) {
                    importDefaultBookSources()
                }
            }.onError {
                it.printOnDebug()
            }
        }
    }

    val httpTTS: List<HttpTTS> by lazy {
        val json =
            String(
                appCtx.assets.open("defaultData${File.separator}httpTTS.json")
                    .readBytes()
            )
        HttpTTS.fromJsonArray(json).getOrElse {
            emptyList()
        }
    }

    val readConfigs: List<ReadBookConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ReadBookConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ReadBookConfig.Config>(json).getOrNull()
            ?: emptyList()
    }

    val txtTocRules: List<TxtTocRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}txtTocRule.json")
                .readBytes()
        )
        GSON.fromJsonArray<TxtTocRule>(json).getOrNull() ?: emptyList()
    }

    val themeConfigs: List<ThemeConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ThemeConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ThemeConfig.Config>(json).getOrNull() ?: emptyList()
    }

    val rssSources: List<RssSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}rssSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<RssSource>(json).getOrDefault(emptyList())
    }

    val coverRule: BookCover.CoverRule by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}coverRule.json")
                .readBytes()
        )
        GSON.fromJsonObject<BookCover.CoverRule>(json).getOrThrow()
    }

    val dictRules: List<DictRule> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}dictRules.json")
                .readBytes()
        )
        GSON.fromJsonArray<DictRule>(json).getOrThrow()
    }

    /**
     * 内置书源(assets/defaultData/bookSources.json)。
     * 出场自带书源是"发现页/搜索能直接调用到"的前提: 没有书源时引擎返回的结果恒为空。
     */
    val bookSources: List<BookSource> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}bookSources.json")
                .readBytes()
        )
        GSON.fromJsonArray<BookSource>(json).getOrNull() ?: emptyList()
    }

    val keyboardAssists: List<KeyboardAssist> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}keyboardAssists.json")
                .readBytes()
        )
        GSON.fromJsonArray<KeyboardAssist>(json).getOrThrow()
    }

    fun importDefaultHttpTTS() {
        appDb.httpTTSDao.all
            .filter { it.id < 0 }
            .forEach { it.clearSharedGlobalState() }
        appDb.httpTTSDao.deleteDefault()
        appDb.httpTTSDao.insert(*httpTTS.toTypedArray())
    }

    fun importDefaultTocRules() {
        appDb.txtTocRuleDao.deleteDefault()
        appDb.txtTocRuleDao.insert(*txtTocRules.toTypedArray())
    }

    fun importDefaultRssSources() {
        appDb.rssSourceDao.all
            .filter { it.sourceGroup == "legado" }
            .forEach { it.clearSharedGlobalState() }
        appDb.rssSourceDao.deleteDefault()
        appDb.rssSourceDao.insert(*rssSources.toTypedArray())
    }

    fun importDefaultDictRules() {
        appDb.dictRuleDao.insert(*dictRules.toTypedArray())
    }

    /**
     * 只补"库里还没有"的出场书源, 已存在(哪怕被用户改过/停用)一律不动。
     * 这样既能保证新装用户发现页非空, 又不会在升级时覆盖用户的修改。
     */
    fun importDefaultBookSources() {
        // 未激活就不导入 —— 这是「内置源要密码才可用」的落地处：
        // 源不进数据库，THP 的搜索/目录/正文自然全都拿不到东西。
        if (!EngineProfile.activated) return
        if (bookSources.isEmpty()) return
        val exists = appDb.bookSourceDao.all.map { it.bookSourceUrl }.toHashSet()
        val add = bookSources.filter { it.bookSourceUrl !in exists }
        if (add.isNotEmpty()) {
            appDb.bookSourceDao.insert(*add.toTypedArray())
        }
    }

    /**
     * 激活成功后立即导入出厂源（不必等下次冷启动）。
     * 返回本次新入库的源数量，供激活页回显。
     */
    fun importDefaultBookSourcesOnActivate(): Int {
        if (!EngineProfile.activated) return 0
        val before = appDb.bookSourceDao.all.size
        importDefaultBookSources()
        return appDb.bookSourceDao.all.size - before
    }

}
