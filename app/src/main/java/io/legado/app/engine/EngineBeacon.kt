package io.legado.app.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.legado.app.service.WebService
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID

/**
 * 阅读引擎的本地自举入口 —— 纯本地, 无账号, 不连接任何云端服务。
 *
 * 1) 启动 THP 本地服务(:1234), 局域网内的阅读前端可直接 HTTP 调用本引擎;
 * 2) 周期发送 THP/1 UDP 广播(:19527), 同网设备可自动发现本引擎, 无需手动填地址。
 *
 * 本引擎只做两件事: 解析书源规则、返回统一格式内容。它不注册到任何服务器,
 * 不需要登录, 也不向任何远端上报本机信息。
 *
 * 发现层严格对齐 THP/1.0 §4.1:
 *   - 报文 `THP/1 HELLO <httpPort> <instanceId> <role> <caps> [name]`
 *   - 广播周期 30s（规范范围 10–300s；早期实现用的 5s 低于下限，属过度广播）
 *   - instanceId 为 UUID 且重启不变
 *   - 优雅下线必须发 `THP/1 BYE <instanceId>`
 *   - 休眠唤醒后立即重播 HELLO，不等下个周期
 */
object EngineBeacon {

    /** 发现广播端口(THP/1) */
    const val BEACON_PORT = 19527

    /**
     * 广播周期(ms)。THP §4.1: 每 30 秒（可调，范围 10–300s）。
     * 早期实现为 5s —— 低于规范下限，会造成不必要的电量与局域网流量开销。
     */
    private const val BROADCAST_INTERVAL_MS = 30_000L

    /**
     * 本引擎能力(THP caps, 见 docs THP §11 caps 注册表)。
     * ★必须与 ThpServer.meta() 的 caps 保持一致：UDP 广播里的 caps 是前端在**发现之前**
     * 唯一的判据，两者不一致会导致"发现时以为不支持，连上后才发现支持"。
     */
    const val CAPS = "m:novel,m:comic,m:music,m:video,post-query"

    /** 对外展示名 */
    const val NAME = "阅读引擎"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var beaconJob: Job? = null

    @Volatile
    private var wakeReceiver: BroadcastReceiver? = null

    private val prefs get() = appCtx.getSharedPreferences("reading_engine", Context.MODE_PRIVATE)

    /** THP/1.0 实例标识(持久化, 供前端去重/BYE)。§4.1 要求为 UUID 且重启不变。 */
    fun instanceId(): String {
        var id = prefs.getString("instance_id", "") ?: ""
        if (id.isEmpty()) {
            // 1.5.3 起改用标准 UUID。老装机器的既有 id 一律保留不改（改了会变成"新设备"）。
            id = UUID.randomUUID().toString()
            prefs.edit().putString("instance_id", id).apply()
        }
        return id
    }

    /** 应用启动 / 开机自启时调用 */
    @Synchronized
    fun start() {
        if (beaconJob?.isActive == true) {
            rebroadcast()
            return
        }
        ThpServer.ensureStarted()
        startUdpBeacon()
        registerWakeReceiver()
    }

    /**
     * 优雅下线 —— THP §4.1 必选。停广播、关 THP 服务、发一次 BYE，
     * 让局域网内的前端立即把本引擎摘掉，而不是等 ttl（3×广播间隔）超时。
     */
    @Synchronized
    fun stop() {
        beaconJob?.cancel()
        beaconJob = null
        wakeReceiver?.let { runCatching { appCtx.unregisterReceiver(it) } }
        wakeReceiver = null
        sendPacket("THP/1 BYE ${instanceId()}")
        ThpServer.stop()
    }

    /** 立即补发一次 HELLO（§4.1: 休眠唤醒后立即重播，不等下个周期） */
    fun rebroadcast() {
        scope.launch { runCatching { sendHello(DatagramSocket().apply { broadcast = true }) } }
    }

    /** THP/1 HELLO <port> <instanceId> engine <caps> <name> (THP/1.0 §4.1) */
    private fun startUdpBeacon() {
        beaconJob = scope.launch {
            runCatching {
                val sock = DatagramSocket()
                sock.broadcast = true
                while (true) {
                    runCatching { sendHello(sock) }
                    delay(BROADCAST_INTERVAL_MS)
                }
            }.onFailure { beaconJob = null }
        }
    }

    private fun sendHello(sock: DatagramSocket) {
        val payload = "THP/1 HELLO ${ThpServer.PORT} ${instanceId()} engine $CAPS $NAME".toByteArray()
        val addr = InetAddress.getByName("255.255.255.255")
        sock.send(DatagramPacket(payload, payload.size, addr, BEACON_PORT))
    }

    private fun sendPacket(text: String) {
        runCatching {
            val payload = text.toByteArray()
            val addr = InetAddress.getByName("255.255.255.255")
            DatagramSocket().use { s ->
                s.broadcast = true
                s.send(DatagramPacket(payload, payload.size, addr, BEACON_PORT))
            }
        }
    }

    /**
     * 亮屏/解锁时立即重播 HELLO。设备休眠会挂起广播协程，
     * 唤醒后若不补发，前端最长要等一个周期(30s)+ttl 才会重新看到本引擎。
     */
    private fun registerWakeReceiver() {
        if (wakeReceiver != null) return
        runCatching {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    rebroadcast()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            appCtx.registerReceiver(r, filter)
            wakeReceiver = r
        }
    }

    /**
     * 本机局域网 IPv4 —— 复用 legado 的网卡枚举(已排除回环/非 IPv4), 优先私有网段
     * (192.168/10./172.16-31)。不依赖 legado 自带的 WebService(:1122), 引擎自己的
     * ThpServer(:1234) 是独立在跑的。
     */
    fun lanHost(): String {
        val v4 = runCatching { NetworkUtils.getLocalIPAddress() }
            .getOrDefault(emptyList())
            .mapNotNull { it.hostAddress }
        v4.firstOrNull { isPrivateV4(it) }?.let { return it }
        v4.firstOrNull()?.let { return it }
        // 兜底: 若 legado 自带 WebService 恰好在跑, 用它的地址
        val raw = runCatching { if (WebService.isRun) WebService.hostAddress else "" }
            .getOrDefault("")
        return raw.removePrefix("http://").removePrefix("https://")
            .substringBefore('/').substringBefore(':')
    }

    private fun isPrivateV4(ip: String): Boolean {
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split('.').getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /** 本机引擎地址(局域网), 未就绪时返回空串 */
    fun lanUrl(): String {
        val host = lanHost()
        return if (host.isEmpty()) "" else "http://$host:${ThpServer.PORT}"
    }
}
