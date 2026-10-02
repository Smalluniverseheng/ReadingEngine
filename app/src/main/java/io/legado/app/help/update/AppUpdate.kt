package io.legado.app.help.update

import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.CoroutineScope

object AppUpdate {

    /**
     * 更新通道 —— 自家引擎清单（EngineUpdateCheck）。
     * 上游 GitHub / 第三方 CDN 一律不接，见 EngineUpdateCheck 与 AppUpdateGitHub 的说明。
     */
    val engineUpdate: AppUpdateInterface by lazy {
        EngineUpdateCheck
    }

    /** 只有一条更新通道，beta 检查与正式检查同源。 */
    fun checkBeta(scope: CoroutineScope): Coroutine<UpdateInfo> =
        EngineUpdateCheck.check(scope)

    data class UpdateInfo(
        val tagName: String,
        val updateLog: String,
        val downloadUrl: String,
        val fileName: String,
        val backupDownloadUrl: String? = null,
        val mirrorDownloadUrl: String? = null,
        val alternateMirrorDownloadUrl: String? = null,
        val size: Long = 0L,
        val createdAt: Long = 0L,
        val isBeta: Boolean = false
    )

    interface AppUpdateInterface {

        fun check(scope: CoroutineScope): Coroutine<UpdateInfo>

    }

}
