package io.legado.app.engine

import android.content.Context
import io.legado.app.service.WebService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 阅读引擎的本地自举入口 —— 纯本地, 无账号, 不连接任何云端服务。
 *
 * 1) 启动 THP 本地服务(:1234), 局域网内的阅读前端可直接 HTTP 调用本引擎;
 * 2) 周期发送 THP/1 UDP 广播(:19527), 同网设备可自动发现本引擎, 无需手动填地址。
 *
 * 本引擎只做两件事: 解析书源规则、返回统一格式内容。它不注册到任何服务器,
 * 不需要登录, 也不向任何远端上报本机信息。
 */
object EngineBeacon {

    /** 发现广播端口(THP/1) */
    const val BEACON_PORT = 19527

    /** 广播周期(ms) */
    private const val BROADCAST_INTERVAL_MS = 5_000L

    /** 本引擎能力(THP caps): 小说 / 漫画 / 听书音频 */
    const val CAPS = "novel,comic,audio"

    /** 对外展示名 */
    const val NAME = "阅读引擎"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    private val prefs get() = appCtx.getSharedPreferences("reading_engine", Context.MODE_PRIVATE)

    /** THP/1.0 实例标识(持久化, 供前端去重/BYE) */
    private val instanceId: String
        get() {
            var id = prefs.getString("instance_id", "") ?: ""
            if (id.isEmpty()) {
                id = "eng-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                prefs.edit().putString("instance_id", id).apply()
            }
            return id
        }

    /** 应用启动 / 开机自启时调用 */
    fun start() {
        if (started) return
        started = true
        ThpServer.ensureStarted()
        startUdpBeacon()
    }

    /** THP/1 HELLO <port> <instanceId> engine <caps> <name> (THP/1.0 §4.1) */
    private fun startUdpBeacon() {
        scope.launch {
            runCatching {
                val sock = DatagramSocket()
                sock.broadcast = true
                val addr = InetAddress.getByName("255.255.255.255")
                val payload = "THP/1 HELLO ${ThpServer.PORT} $instanceId engine $CAPS $NAME".toByteArray()
                while (true) {
                    runCatching { sock.send(DatagramPacket(payload, payload.size, addr, BEACON_PORT)) }
                    delay(BROADCAST_INTERVAL_MS)
                }
            }
        }
    }

    /** 本机局域网 IP(WebService.hostAddress 形如 http://192.168.1.5:1122, 这里只取 host) */
    fun lanHost(): String {
        if (!WebService.isRun) return ""
        val raw = WebService.hostAddress
        if (raw.isEmpty()) return ""
        return raw.removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
    }

    /** 本机引擎地址(局域网), 未就绪时返回空串 */
    fun lanUrl(): String {
        val host = lanHost()
        return if (host.isEmpty()) "" else "http://$host:${ThpServer.PORT}"
    }
}
