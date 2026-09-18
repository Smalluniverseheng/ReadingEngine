package io.legado.app.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.service.WebService

/** 开机自启: 引擎服务随系统启动, 无需手动打开 App */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            runCatching {
                WebService.startForeground(context)
                EngineBeacon.start()
            }
        }
    }
}
