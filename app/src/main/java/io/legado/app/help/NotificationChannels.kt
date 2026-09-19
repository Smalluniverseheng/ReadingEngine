package io.legado.app.help

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import io.legado.app.R
import io.legado.app.constant.AppConst
import splitties.init.appCtx

/**
 * 通知渠道创建 —— 必须"同步、最早、幂等"完成。
 *
 * ## 为什么单独抽出来
 *
 * 所有后台服务（[io.legado.app.base.BaseService] 的子类）都会在 `onStartCommand` 里调用
 * `startForeground()` 挂常驻通知。若那一刻渠道还不存在，系统会抛
 * `RemoteServiceException$CannotPostForegroundServiceNotificationException`
 * （"Bad notification for startForeground"）**直接杀掉整个进程**。
 *
 * 关键在于：该异常由系统侧（ActivityManager）**异步投递到应用主线程的 Handler**，
 * 不是在 `startForeground()` 调用栈里同步抛出 —— 所以
 * `runCatching { startForeground(...) }` 根本拦不住它，任何 try/catch 都无效。
 * 唯一的解法就是**保证渠道先于服务存在**。
 *
 * ## 踩过的坑（1.5.2 及更早）
 *
 * 渠道创建原先是 `Application.onCreate()` 里**异步协程**中的一步，排在 `LogUtils.init`、
 * `Cronet.preDownload()`（联网下载！）之后；而"开机即用"又让 `App.onCreate()` 最开头就
 * `startForegroundService(WebService)`。两者必然竞态：
 * 冷启动 / 新装首次运行（Cronet 要现下）时渠道还没建好服务就起了 →
 * **新装首次启动稳定崩溃，App 完全不可用**。
 *
 * ## 使用约定
 *
 * - `App.onCreate()` 开头**同步**调用一次（任何服务启动之前）
 * - `BaseService.onStartCommand()` 里再兜一次（覆盖开机广播、磁贴、外部显式启动等入口）
 * - 幂等，重复调用零成本；低版本（< O）直接返回 true
 */
object NotificationChannels {

    @Volatile
    private var ensured = false

    /**
     * 确保三个常驻通知渠道存在。幂等。
     *
     * @return true 表示渠道已就绪（低版本恒 true）；false 表示创建失败，
     *         调用方应避免此时启动前台服务。
     */
    fun ensure(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (ensured) return true
        val ok = runCatching {
            val nm = appCtx.getSystemService(NotificationManager::class.java)
                ?: return@runCatching false
            nm.createNotificationChannels(
                listOf(
                    channel(AppConst.channelIdDownload, R.string.action_download),
                    channel(AppConst.channelIdReadAloud, R.string.read_aloud),
                    channel(AppConst.channelIdWeb, R.string.web_service),
                )
            )
            true
        }.getOrDefault(false)
        if (ok) ensured = true
        return ok
    }

    private fun channel(id: String, nameRes: Int): NotificationChannel =
        NotificationChannel(id, appCtx.getString(nameRes), NotificationManager.IMPORTANCE_DEFAULT)
            .apply {
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
}
