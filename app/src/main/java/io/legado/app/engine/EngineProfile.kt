package io.legado.app.engine

import android.content.Context
import io.legado.app.BuildConfig
import splitties.init.appCtx
import java.security.MessageDigest

/**
 * 引擎档案（编译期五产物化 + 运行期首次激活门）。
 *
 * 五产物共用同一份源码，靠 productFlavor 注入的 BuildConfig 字段分化：
 *
 * | flavor | 包名后缀 | ENGINE_MODULES | ENGINE_SOURCE_TYPES | 出厂源包 |
 * |--------|----------|----------------|---------------------|----------|
 * | app    | (无)     | 四模块          | 空(不过滤)           | 全量 3898 |
 * | novel  | .novel   | novel          | 0,3                 | 3610 |
 * | comic  | .comic   | comic          | 2                   | 101 |
 * | music  | .music   | music          | 1                   | 65 |
 * | video  | .video   | video          | 4                   | 122 |
 *
 * ★为什么能力白名单要编译期注入而不是运行时配置：
 * UDP 广播里的 caps 是前端**在发现之前**唯一的判据（见 EngineBeacon.CAPS 注释）。
 * 如果「漫画引擎」广播 `m:novel,m:comic,...`，前端会把小说搜索也派给它，
 * 而它库里根本没有文本源 —— 表现为"连上了但搜出来永远是空"，比不广播更糟。
 *
 * ── 首次激活 ──
 * 内置源是私货，装完第一次打开必须输入密码才解锁：
 *  · 未激活：不把出厂源写进数据库；THP 模块端点一律 403 ACTIVATION_REQUIRED；
 *           meta 的 caps 为空数组（前端据此跳过本引擎，而不是反复重试）。
 *  · 已激活：正常导入源、正常广播 caps。
 * 密码只存 SHA-256(盐 + 明文)，不落明文；默认密码见 [DEFAULT_PASSWORD]。
 */
object EngineProfile {

    /**
     * 出厂默认密码。
     * 用户要求「先默认一到六」——即 `123456`。四类引擎通用，可在引擎面板里改。
     */
    const val DEFAULT_PASSWORD = "123456"

    /** 盐：与包名无关的固定值。只防「拿到 hash 直接反查常见密码表」，不防暴力破解。 */
    private const val SALT = "thirdhub.reading-engine.v1"

    private const val PREF = "reading_engine"
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_PWD_HASH = "pwd_hash"

    private val prefs get() = appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ────────────────── 产物身份 ──────────────────

    /** 本产物的对外名（「漫画引擎」等）。UDP 广播与 THP meta 都用它。 */
    val displayName: String get() = BuildConfig.ENGINE_NAME

    /** 本产物声明的模块白名单。 */
    val modules: List<String>
        get() = BuildConfig.ENGINE_MODULES.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 本产物允许参与搜索的书源类型；空集 = 不过滤。 */
    val sourceTypes: Set<Int>
        get() = BuildConfig.ENGINE_SOURCE_TYPES
            .split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    /** 是否四合一产物（四类源都装）。 */
    val isAllInOne: Boolean get() = modules.size >= 4

    fun allowsModule(module: String): Boolean = modules.isEmpty() || module in modules

    fun allowsSourceType(type: Int): Boolean = sourceTypes.isEmpty() || type in sourceTypes

    /** THP caps 数组（`m:<module>` + post-query）。与 EngineBeacon.CAPS 必须同源。 */
    val caps: List<String>
        get() = modules.map { "m:$it" } + "post-query"

    /** UDP 广播用的逗号串。 */
    val capsString: String get() = caps.joinToString(",")

    // ────────────────── 首次激活门 ──────────────────

    /** 是否已激活（未激活时内置源不可用、不声明白名单外的能力）。 */
    val activated: Boolean get() = prefs.getBoolean(KEY_ACTIVATED, false)

    /** 用户改过密码（未改过则用出厂默认）。 */
    private val hasCustomPassword: Boolean get() = !prefs.getString(KEY_PWD_HASH, null).isNullOrEmpty()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 校验密码。未改过密码时同时接受出厂默认与用户设置值
     * （覆盖安装的老逻辑不会留下 KEY_PWD_HASH，此时只认默认值）。
     */
    fun verifyPassword(input: String): Boolean {
        if (input.isEmpty()) return false
        val stored = prefs.getString(KEY_PWD_HASH, null)
        if (stored.isNullOrEmpty()) return input == DEFAULT_PASSWORD
        return sha256(SALT + input) == stored
    }

    /** 修改密码。返回 false 表示新密码不合法（长度 4..64）。 */
    fun setPassword(newPassword: String): Boolean {
        if (newPassword.length < 4 || newPassword.length > 64) return false
        prefs.edit().putString(KEY_PWD_HASH, sha256(SALT + newPassword)).apply()
        return true
    }

    /** 激活（校验通过后调用）。调用方负责随后导入出厂源。 */
    fun activate() {
        prefs.edit().putBoolean(KEY_ACTIVATED, true).apply()
    }

    /** 反激活：局域网里临时停用内置源（内置源已入库则不动，只是引擎不再对外服务）。 */
    fun deactivate() {
        prefs.edit().putBoolean(KEY_ACTIVATED, false).apply()
    }

    /** 供面板展示：当前密码来源。 */
    val passwordSource: String
        get() = if (hasCustomPassword) "自定义密码" else "出厂默认密码"
}
