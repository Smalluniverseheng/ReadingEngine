package io.legado.app.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.help.NotificationChannels
import io.legado.app.service.WebService

/** 开机自启: 引擎服务随系统启动, 无需手动打开 App */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 渠道必须先于前台服务存在, 否则 startForeground 会被系统异步抛异常杀进程
            // (Application.onCreate 已兜一次, 此处防御性再兜一次; 幂等)
            NotificationChannels.ensure()
            runCatching {
                WebService.startForeground(context)
                EngineBeacon.start()
            }
        }
    }
}
