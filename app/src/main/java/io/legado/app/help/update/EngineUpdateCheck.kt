package io.legado.app.help.update

import com.google.gson.Gson
import io.legado.app.BuildConfig
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 引擎更新公告 —— 走**自家**引擎清单 downloads/engine/engines.json。
 *
 * 与 AppUpdateGitHub 的分工
 * ──────────────────────────────────────────────────────────────
 * 上游 Legado 的 GitHub Releases 查询与第三方 CDN 镜像**仍然不接**
 * （见 AppUpdateGitHub 的中性化说明）。本类是自己的更新通道：
 * 清单在自己站点、安装包在自己桶里，与管理台「引擎中心」读的是同一份文件。
 *
 * 行为约定（别改坏任何一条）：
 *  · 启动时的检查由 MainActivity.upVersion 节流（24 小时一次 + 「自动更新」开关），
 *    本类自己不做节流、不落任何状态；
 *  · 任何失败（断网 / 清单缺失 / JSON 坏 / 变体对不上）都**只抛异常不崩溃**，
 *    启动路径没有 onError 处理器也不会有副作用；
 *  · 「没有新版本」也走异常（文案「已是最新版本」），由手动检查的 toast 呈现，
 *    启动路径静默 —— 不打扰。
 *
 * 清单格式（生成方：_engine_publish.mjs，消费方：管理台 js/admin.js + 本类）：
 *   {
 *     "version": "1.8.0",
 *     "updatedAt": "2026-10-01 15:15:00",
 *     "releasedAt": "2026-10-01 15:15:00",
 *     "minAppVersion": "4.44.0",
 *     "changelog": "1.8.0：…",
 *     "items": [
 *       { "id": "app|novel|comic|music|video", "name": "…", "version": "1.8.0",
 *         "note": "…", "file": "reading-engine-1.8.0.apk", "size": 123,
 *         "sha256": "…", "url": "https://…" }
 *     ]
 *   }
 * 变体对齐：items[].id 与 BuildConfig.FLAVOR（app/novel/comic/music/video）同名。
 */
object EngineUpdateCheck : AppUpdate.AppUpdateInterface {

    /** 引擎清单地址。改这里必须同步改管理台 js/admin.js 的 ENGINE_MANIFEST 消费逻辑。 */
    const val MANIFEST_URL = "https://thirdhub.pages.dev/downloads/engine/engines.json"

    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000

    override fun check(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            val body = fetchManifest()
            val manifest = parseManifestJson(body)
                ?: throw NoStackTraceException("引擎清单读取失败")
            pickUpdateInfo(manifest, BuildConfig.FLAVOR, BuildConfig.VERSION_NAME)
                ?: throw NoStackTraceException("已是最新版本")
        }
    }

    /**
     * 阻塞式 GET，**必须在 IO 线程调用**（照 EngineProfile.syncCloudPassword 的口径）。
     * 失败一律抛异常，由 check() 的调用方消化。
     */
    private fun fetchManifest(): String {
        val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode !in 200..299) {
                throw NoStackTraceException("引擎清单 HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/** 清单顶层。字段可缺省（旧清单没有 releasedAt / minAppVersion），一律允许为 null。 */
internal data class EngineManifest(
    val version: String? = null,
    val updatedAt: String? = null,
    val releasedAt: String? = null,
    val minAppVersion: String? = null,
    val changelog: String? = null,
    val note: String? = null,
    val items: List<EngineManifestItem>? = null
)

internal data class EngineManifestItem(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val note: String? = null,
    val file: String? = null,
    val size: Long = 0L,
    val sha256: String? = null,
    val url: String? = null
)

/** JSON → EngineManifest。坏 JSON / 空文本返回 null，不抛。 */
internal fun parseManifestJson(body: String?): EngineManifest? {
    val text = body?.trim().orEmpty()
    if (text.isEmpty()) return null
    return try {
        Gson().fromJson(text, EngineManifest::class.java)
    } catch (e: Exception) {
        null
    }
}

/**
 * 纯决策：清单 + 当前变体 + 当前版本 → 要不要提示、提示什么。
 *
 * 返回 null = 不提示（已是最新 / 变体对不上 / 没有可下载地址）。
 * 版本比较复用 AppUpdateSelector 的 compareReleaseVersions（与旧通道同一把尺子）。
 */
internal fun pickUpdateInfo(
    manifest: EngineManifest,
    variantId: String,
    currentVersion: String
): AppUpdate.UpdateInfo? {
    val item = manifest.items?.firstOrNull { it.id == variantId } ?: return null
    val remoteVersion = item.version?.takeIf { it.isNotBlank() }
        ?: manifest.version?.takeIf { it.isNotBlank() }
        ?: return null
    if (compareReleaseVersions(remoteVersion, currentVersion) <= 0) return null
    val downloadUrl = item.url?.takeIf { it.isNotBlank() } ?: return null
    val log = buildUpdateLog(manifest, item)
    return AppUpdate.UpdateInfo(
        tagName = remoteVersion,
        updateLog = log,
        downloadUrl = downloadUrl,
        fileName = item.file?.takeIf { it.isNotBlank() } ?: downloadUrl.substringAfterLast('/'),
        backupDownloadUrl = null,
        mirrorDownloadUrl = null,
        alternateMirrorDownloadUrl = null,
        size = item.size,
        createdAt = parseReleasedAtMillis(manifest.releasedAt ?: manifest.updatedAt),
        isBeta = false
    )
}

/**
 * 更新说明 = 清单 changelog（缺省退回条目 note），再补一行 minAppVersion 提示。
 * 空说明也要留一句兜底 —— UpdateDialog 拿到空正文会直接 dismiss（"没有数据"）。
 */
internal fun buildUpdateLog(manifest: EngineManifest, item: EngineManifestItem): String {
    val body = manifest.changelog?.takeIf { it.isNotBlank() }
        ?: item.note?.takeIf { it.isNotBlank() }
        ?: "引擎有新版本，建议更新。"
    val minApp = manifest.minAppVersion?.takeIf { it.isNotBlank() } ?: return body
    return body.trimEnd() + "\n\n（需要客户端 App $minApp 及以上）"
}

/** "2026-10-01 15:15:00" / "2026-10-01 15:15" / "2026-10-01" → epoch millis；解析不了回 0（对话框不显示日期）。 */
internal fun parseReleasedAtMillis(text: String?): Long {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return 0L
    for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
        try {
            val ldt = LocalDateTime.parse(t, DateTimeFormatter.ofPattern(pattern))
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            // 试下一个格式
        }
    }
    // 只有日期没有时间必须走 LocalDate：拿 LocalDateTime 解析纯日期会因
    // 缺时分秒字段直接抛（"time is not present"），结果是明明写了发布日期却被
    // 当成"没有日期"。CI 单测 releasedAtParsesLeniently 抓过这个坑，别退回。
    try {
        return LocalDate.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        // 认不出的写法（如"昨天"）回 0：宁可不显示日期，不显示错日期
    }
    return 0L
}
