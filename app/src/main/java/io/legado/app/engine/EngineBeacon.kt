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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
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
     *
     * ★2026-09-30 五产物化：不再硬编码四模块，改为按 productFlavor 注入的
     * ENGINE_MODULES 生成（见 EngineProfile.caps）。
     * ★2026-10-02 能力不设限（用户指令「它能支持什么就让它支持什么」）：
     * 五产物一律声明全四模块，广播的就是代码真实能力；某引擎搜某类型为空
     * 只是「它没装这类源」，多引擎归并按「谁有结果」聚合，不再靠阉割 caps 躲。
     */
    val CAPS: String get() = EngineProfile.capsString

    /** 对外展示名（阅读引擎 / 漫画引擎 / …），来自 productFlavor。 */
    val NAME: String get() = EngineProfile.displayName

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

    /**
     * ★2026-09-19 修复「前端发现引擎时好时坏 / 锁屏后引擎就消失」的**根因之一**。
     *
     * 旧实现只往 `255.255.255.255`（**有限广播**）发一份，这在下面三种常见环境下必坏：
     *  1) 手机同时开着**移动数据 / VPN**（代理类 App 极常见）时，有限广播由内核按
     *     **默认路由**挑出口 —— 可能从蜂窝或 tun 隧道出去，**局域网内一台都收不到**；
     *  2) 大量 AP / 路由器**直接丢弃有限广播**，但放行**子网定向广播**；
     *  3) 多网卡（WiFi + 有线 + 热点）时只会走其中一个网卡。
     *
     * 子网定向广播（如 192.168.1.255）的目的地址**落在该网卡自己的子网内**，
     * 内核必然从**那块网卡**发出 → 出口正确，且 AP 普遍放行。
     */
    private fun directedBroadcasts(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            for (ni in Collections.list(ifaces)) {
                if (!ni.isUp) continue
                for (ia in ni.interfaceAddresses) {
                    // IPv6 没有广播地址，interfaceAddresses 里 broadcast 为 null
                    val b = ia.broadcast ?: continue
                    if (b is Inet4Address && !b.isAnyLocalAddress) out.add(b)
                }
            }
        }
        return out
    }

    /** 把报文发给「每个网卡的子网定向广播」+ 有限广播兜底 */
    private fun sendToAll(sock: DatagramSocket, payload: ByteArray) {
        val targets = directedBroadcasts()
        for (a in targets) {
            runCatching { sock.send(DatagramPacket(payload, payload.size, a, BEACON_PORT)) }
        }
        // 兜底：老环境 / 单网卡 / 定向广播拿不到时，有限广播仍可能生效
        runCatching {
            sock.send(DatagramPacket(payload, payload.size,
                InetAddress.getByName("255.255.255.255"), BEACON_PORT))
        }
    }

    private fun sendHello(sock: DatagramSocket) {
        val payload = "THP/1 HELLO ${ThpServer.PORT} ${instanceId()} engine $CAPS $NAME".toByteArray()
        sendToAll(sock, payload)
    }

    private fun sendPacket(text: String) {
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                sendToAll(s, text.toByteArray())
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
