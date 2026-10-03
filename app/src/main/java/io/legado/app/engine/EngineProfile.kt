package io.legado.app.engine

import android.content.Context
import io.legado.app.BuildConfig
import org.json.JSONObject
import splitties.init.appCtx
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 引擎档案（编译期身份 + 运行期首次激活门）。
 *
 * ★ 2026-10-02 起本仓是**单产物**：一个开源项目（Legado 系基座）只出一个 App。
 *   此前用 productFlavor 切 5 个产物（app/novel/comic/music/video）的做法，
 *   在用户眼里就是「同一个软件切了五份」—— 正是他否掉的形态（见
 *   docs/PLAN-4.56-ENGINES.md §一）。四个独立引擎各有各的上游
 *   （Legado / Venera / LX Music / TVBox），各在自己的仓里出包，不在本仓切分。
 *
 *   身份由 defaultConfig 注入的 BuildConfig 字段决定：
 *   · ENGINE_NAME          = 「阅读引擎」
 *   · ENGINE_MODULES       = novel,comic,music,video  ← 四类**全部保留**
 *   · ENGINE_SOURCE_TYPES  = 空（不按类型过滤源）
 *
 * ★ 能力不设限（用户指令：「谁说一个引擎只能支持一种功能？不要强制要求这个
 *   引擎能干啥，它能支持什么就让它支持什么」）：本基座（开源阅读 Legado 系）
 *   天生支持 小说+漫画+音乐+视频 四类内容，一律声明全四模块；
 *   ENGINE_SOURCE_TYPES 只是「出厂源包按类型策展」的标签（供展示/审计），
 *   **不卡搜索范围** —— 用户导入任何类型的源都参与搜索（见 SearchScope）。
 *
 * （旧设计的顾虑「没源的模块别广播 caps」在多引擎并存下不再成立：一个引擎搜
 *   某类型为空只是「它没有这类源」，同网络下别的引擎会有 —— 前端多引擎归并
 *   去重本来就是按「谁有结果」聚合的。）
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

    /** 管理员在后台「引擎中心」设的那份密码指纹（由 [syncCloudPassword] 拉取后缓存）。 */
    private const val KEY_CLOUD_HASH = "cloud_pwd_hash"
    private const val KEY_CLOUD_VER = "cloud_pwd_ver"

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

    /** 是否「四类全装」的产物。★1.10.0 单产物化后本仓只剩一个 App，
     *  ENGINE_SOURCE_TYPES 为空（不按类型策展源）且四模块俱全 → 恒为 true。 */
    val isAllInOne: Boolean get() = modules.size >= 4 && sourceTypes.isEmpty()

    fun allowsModule(module: String): Boolean = modules.isEmpty() || module in modules

    fun allowsSourceType(type: Int): Boolean = sourceTypes.isEmpty() || type in sourceTypes

    /**
     * THP caps 数组（`m:<module>` + post-query）。与 EngineBeacon.CAPS 必须同源。
     *
     * ★2026-10-03 新增两项（协议只增不减，见 THP §15）：
     *  · `search-mode` —— `/thp/search` 认 `mode` 参数（fuzzy 模糊 / exact 精确 / deep 搜到底）。
     *    为什么要声明：前端在**发现阶段**（还没连上）就要知道能不能给用户那个开关，
     *    不声明的话只能连上以后试错，试错失败的表现是"开关点了没反应"。
     *  · `stream-search` —— `/thp/search?...&stream=1` 返回 NDJSON 流，结果边扫边推，
     *    不用等整轮扫完（治「等半天然后一下子冒出来」的体感问题）。
     */
    val caps: List<String>
        get() = modules.map { "m:$it" } + "post-query" + "search-mode" + "stream-search"

    /** UDP 广播用的逗号串。 */
    val capsString: String get() = caps.joinToString(",")

    // ────────────────── 首次激活门 ──────────────────

    /**
     * ★ 激活门总开关 —— 2026-10-01 由用户指定：自用阶段不设密码，装完即用。
     *
     * 为什么是「旁路」而不是「删除」：
     *   用户原话是「先不用密码，但框架先买好；以后想加密码的时候还能加」。
     *   所以校验 / 改密 / 后台同步 / 激活页这一整套一行未删，
     *   只用这一个常量把入口整体旁路。日后要恢复门禁，把它改成 true 即回到旧行为，
     *   不需要重新实现任何东西。
     *
     * 关闭后的行为（=「开箱即用」）：
     *   · 内置源冷启动直接入库（App.onCreate → importDefaultBookSourcesOnActivate）
     *   · 欢迎页不再跳激活页，直接进主界面
     *   · THP 端点不再返回 403 activation_required，meta 的 activationRequired 恒为 false
     *   · UDP 照常广播 caps，前端发现即可用
     */
    const val PASSWORD_GATE_ENABLED = false

    /** 是否已激活（未激活时内置源不可用、不声明白名单外的能力）。 */
    val activated: Boolean
        get() = !PASSWORD_GATE_ENABLED || prefs.getBoolean(KEY_ACTIVATED, false)

    /** 用户改过密码（未改过则用出厂默认）。 */
    private val hasCustomPassword: Boolean get() = !prefs.getString(KEY_PWD_HASH, null).isNullOrEmpty()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 校验密码。优先级：本机自定义 > 后台（管理员）设置 > 出厂默认。
     *
     * · 本机自定义（KEY_PWD_HASH）：用户在 App 面板里改过 → 以本机为准，
     *   否则"本机改了密码"会被后台把旧密码又"同步"回来。
     * · 后台设置（KEY_CLOUD_HASH）：管理员在「引擎中心」设的那份，
     *   由 [syncCloudPassword] 拉取后缓存。**没联网拉到就跳过这一档**。
     * · 出厂默认：两档都为空时（覆盖安装的老逻辑不会留下任何 hash）。
     */
    fun verifyPassword(input: String): Boolean {
        if (input.isEmpty()) return false
        val local = prefs.getString(KEY_PWD_HASH, null)
        if (!local.isNullOrEmpty()) return sha256(SALT + input) == local
        val cloud = prefs.getString(KEY_CLOUD_HASH, null)
        if (!cloud.isNullOrEmpty()) return sha256(SALT + input) == cloud
        return input == DEFAULT_PASSWORD
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
        get() = when {
            hasCustomPassword -> "本机自定义密码"
            !prefs.getString(KEY_CLOUD_HASH, null).isNullOrEmpty() ->
                "管理员后台设置" + cloudPasswordVersion.takeIf { it.isNotEmpty() }?.let { "（第 $it 版）" }.orEmpty()
            else -> "出厂默认密码"
        }

    // ────────────────── 后台同步（管理员在「引擎中心」改的密码） ──────────────────

    /**
     * 后台地址与**公开**读键。
     *
     * publishable key 本来就是随网页一起下发的公开值（网页端 js/config.js 里就写着它），
     * 不是秘密：它只能调公开的 security definer RPC（engine_pwd_fetch），
     * 既读不到被 RLS 收紧的 th_kv，也改不了任何东西。
     * （security-fix.sql 2026-08-27 起：th_kv 匿名只读 `vendor:*`、写入仅 service_role。
     *  所以旧版「直读 th_kv?key=in.(engine_pwd_hash,…)」永远返回 0 行 —— 见 syncCloudPassword。）
     */
    private const val CLOUD_URL = "https://mxvxlgjzeboktufumxbp.supabase.co"
    private const val CLOUD_PUBLISHABLE = "sb_publishable_WzUzAQK5cOEsn7QwFB2cAw_ubIkG7RJ"

    /** 后台设置的密码版本号（后台每改一次 +1）。空 = 没设过 / 没同步成功。 */
    val cloudPasswordVersion: String get() = prefs.getString(KEY_CLOUD_VER, "").orEmpty()

    /**
     * 从后台拉一次激活密码指纹 —— 让「管理员在后台改密码，各端同步一次即跟随」成真。
     *
     * ★ 只读 + best-effort，**绝不 fail-closed**：
     *  · 断网 / 超时 / RPC 未部署(404) / 返回不是 JSON 对象 → 直接返回 false，
     *    已缓存的指纹与出厂默认都不动。否则用户一断网就被自己的引擎锁在门外。
     *  · 后台没设过（RPC 回 set=false）→ 清掉缓存，干净地退回「出厂默认」。
     *
     * 内部是阻塞式 HTTP，**必须在 IO 线程调用**。
     *
     * @return true = 确实从后台取到了数据（不代表密码一定被改过）
     */
    fun syncCloudPassword(): Boolean {
        // ★ 必须走 RPC，不能直读 th_kv。
        //   security-fix.sql(2026-08-27) 把 th_kv 的 RLS 收紧成「匿名只读 vendor:*、
        //   写入仅 service_role」之后，匿名 `select th_kv?key=in.(engine_pwd_hash,…)`
        //   会稳定返回 **0 行**（HTTP 200，空数组）—— 直读是一条永远拿不到数据的死路。
        //   已实测确认：anon 读全表 0 行、anon 写 42501。
        //   engine_pwd_fetch() 是 security definer，绕过 RLS，只回 hash/版本号、不回明文。
        //   该 RPC 未部署时 PostgREST 返回 404 → 本函数返回 false，行为与「没网」一致。
        val url = "$CLOUD_URL/rest/v1/rpc/engine_pwd_fetch"
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 4000
                conn.readTimeout = 6000
                conn.setRequestProperty("apikey", CLOUD_PUBLISHABLE)
                conn.setRequestProperty("Authorization", "Bearer $CLOUD_PUBLISHABLE")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write("{}".toByteArray()) }
                if (conn.responseCode !in 200..299) {
                    false
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val o = JSONObject(body)
                    // set=false（后台从未设过 / 已被「恢复出厂默认」清掉）→ 清空缓存，
                    // 干净地退回 App 里烤死的 123456，而不是留着一份过期指纹把人锁在门外。
                    val hash = if (o.optBoolean("set", false)) o.optString("hash").trim() else ""
                    val ver = o.optString("ver").trim()
                    prefs.edit()
                        .putString(KEY_CLOUD_HASH, hash)
                        .putString(KEY_CLOUD_VER, ver)
                        .apply()
                    true
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // 联网失败一律当作"没同步到"，保留原状。
            false
        }
    }
}
