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
            // ★2026-10-03 拆成两个独立 runCatching（与 App.onCreate 同因）：
            //   BOOT_COMPLETED 正是「从后台启动前台服务」被系统拦截的典型场景
            //   （ForegroundServiceStartNotAllowedException），旧写法下
            //   WebService 一抛就**连坐**跳过 EngineBeacon.start()，
            //   结果「开机自启的引擎」只起了附属的 WebService，引擎本体永远不在。
            //   这是「引擎明明装了、前端却连不上」的直接原因之一。
            runCatching {
                WebService.startForeground(context)
            }.onFailure {
                android.util.Log.w("BootReceiver", "WebService 启动失败（不影响引擎本体）: $it")
            }
            runCatching {
                EngineBeacon.start()
            }.onFailure {
                android.util.Log.e("BootReceiver", "EngineBeacon 启动失败: $it", it)
            }
        }
    }
}
