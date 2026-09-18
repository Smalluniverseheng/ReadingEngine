package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.R
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope
import splitties.init.appCtx

/**
 * 中性化说明
 * ──────────────────────────────────────────────────────────────
 * 本引擎是纯本地软件, 不含任何在线更新通道:
 *   · 已移除上游 Legado 的 GitHub Releases 查询
 *   · 已移除第三方 CDN 镜像(cdn.mgz.la / cdn.gigu.edu.kg 等)的解析入口
 * 保留本类仅为满足 AppUpdateInterface 的形状, check/checkBeta 一律直接返回失败,
 * 不发起任何网络请求。更新引擎请重新获取新的安装包。
 */
@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            throw NoStackTraceException(appCtx.getString(R.string.local_engine_update_disabled))
        }
    }

    fun checkBeta(scope: CoroutineScope): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            throw NoStackTraceException(appCtx.getString(R.string.local_engine_update_disabled))
        }
    }
}

internal fun AppReleaseInfo.toUpdateInfo(isBeta: Boolean = false): AppUpdate.UpdateInfo {
    val primaryUrl = if (isBeta) downloadUrl else resolveAppUpdateDownloadUrl(name, downloadUrl)
    val mirrorUrl = if (isBeta) null else resolveAppUpdateMirrorUrl(name, primaryUrl)
    val alternateMirrorUrl = if (isBeta) {
        null
    } else {
        resolveAppUpdateAlternateMirrorUrl(name, primaryUrl, mirrorUrl)
    }
    return AppUpdate.UpdateInfo(
        tagName = versionName,
        updateLog = note,
        downloadUrl = primaryUrl,
        fileName = name,
        backupDownloadUrl = if (isBeta) null else resolveAppUpdateBackupUrl(primaryUrl, downloadUrl),
        mirrorDownloadUrl = mirrorUrl,
        alternateMirrorDownloadUrl = alternateMirrorUrl,
        size = size,
        createdAt = createdAt,
        isBeta = isBeta
    )
}
