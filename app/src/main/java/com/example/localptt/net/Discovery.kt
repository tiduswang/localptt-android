package com.example.localptt.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 零配置自动发现：让「装了 App 的设备自动出现在列表里」成为可能，无需手动输入 IP。
 *
 * 三条通道互为兜底（任一可用即可发现）：
 *  1. UDP 广播（主力）：主机每 2s 向 255.255.255.255 及各网卡子网广播地址发送 beacon；
 *     同网段（普通 2.4G 路由器 / 软 AP / Wi-Fi Direct 组）内所有客户端都能收到。
 *  2. mDNS/NSD：主机注册 `_localptt._tcp` 服务，客户端用 NsdManager 解析出 IP:端口。
 *     AP 隔离拦截广播时，组播通常仍可通行。
 *  3. Wi-Fi Direct 原生发现：见 WifiDirectManager.discoverPeers（无路由器场景）。
 */

/** 发现到的一台主机。source 标注发现方式；extra 可携带原始对象（如 WifiP2pDevice）。 */
data class DiscoveredHost(
    val id: String,
    val name: String,
    val ip: String,
    val ctrlPort: Int,
    val source: String,
    val lastSeen: Long = System.currentTimeMillis(),
    val extra: Any? = null
)

object Discovery {
    const val BEACON_PORT = 8890
    const val SERVICE_TYPE = "_localptt._tcp."
    private const val TAG = "Discovery"
    const val BEACON_INTERVAL_MS = 2000L
    const val HOST_TIMEOUT_MS = 6000L
}

/** 按网卡名前缀挑出第一个可用的 IPv4 地址。 */
private fun ipv4On(pick: (String) -> Boolean): String? = runCatching {
    NetworkInterface.getNetworkInterfaces()?.let { e ->
        e.toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { pick(it.name.lowercase()) }
            .flatMap { it.interfaceAddresses.asSequence() }
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }
}.getOrNull()

/** 本机任意一块可用网卡的 IPv4（含移动数据；仅作最后兜底）。 */
fun localIpv4(): String? = ipv4On { true }

/**
 * **路由器 / 普通 Wi-Fi 局域网** 上的 IPv4；不含 p2p 与移动数据。
 * 有值说明「两台手机可以靠同一个路由器互联」，此时完全不需要 Wi-Fi Direct。
 */
fun lanIpv4(): String? = ipv4On {
    it.startsWith("wlan") || it.startsWith("eth") || it.startsWith("ap")
}

/** Wi-Fi Direct 组（p2p0）上的 IPv4；客户端通常是 192.168.49.x。 */
fun p2pIpv4(): String? = ipv4On { it.startsWith("p2p") }

/**
 * 本机在 **Wi-Fi / 以太网 / Wi-Fi Direct** 网卡上的 IPv4；没有则返回 null。
 *
 * 与 [localIpv4] 的区别：后者会把移动数据网卡的地址也算进来。判断「此刻是否在
 * 一个可用的本地网络里」必须用这个，否则会误把 4G 当成局域网。
 */
fun wifiIpv4(): String? = ipv4On {
    it.startsWith("wlan") || it.startsWith("eth") || it.startsWith("ap") || it.startsWith("p2p")
}

/** 192.168.49.x 是 Wi-Fi Direct 组长的标准地址段。 */
fun isP2pAddress(ip: String): Boolean =
    ip.startsWith("192.168.49.") || ip == p2pIpv4()

/** 计算本机各 IPv4 网卡对应的定向/子网广播地址（含 255.255.255.255）。 */
private fun broadcastTargets(): List<InetAddress> {
    val out = LinkedHashSet<InetAddress>()
    out += InetAddress.getByName("255.255.255.255")
    runCatching {
        NetworkInterface.getNetworkInterfaces()?.let { e ->
            e.toList().forEach { ni ->
                if (ni.isLoopback || !ni.isUp) return@forEach
                ni.interfaceAddresses.forEach { ia ->
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val bits = ia.networkPrefixLength.toInt()
                        val ipInt = ByteBuffer.wrap(a.address).int
                        val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
                        val bcInt = ipInt or mask.inv()
                        out += InetAddress.getByAddress(
                            ByteBuffer.allocate(4).putInt(bcInt).array()
                        )
                    }
                }
            }
        }
    }
    return out.toList()
}

/**
 * 主机侧：建组成功后启动，周期广播自身存在（UDP beacon + NSD 注册）。
 * 客户端据此自动出现在「附近的主机」列表中。
 */
class HostBeacon(
    private val ctx: Context,
    private val hostId: String,
    private val hostName: String,
    private val ctrlPort: Int = 8888
) {
    private val scope = netScope()
    private var socket: DatagramSocket? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var mcLock: WifiManager.MulticastLock? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        scope.launch { udpLoop() }
        registerNsd()
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        unregisterNsd()
        runCatching { mcLock?.release() }; mcLock = null
        scope.cancel()
    }

    private fun udpLoop() {
        while (running) {
            try {
                if (socket == null || socket!!.isClosed) {
                    // 发送用随机端口（0 = 系统分配），接收方在固定 8890 上收
                    socket = openUdpSocket(0)
                    if (socket == null) { Thread.sleep(2000); continue }
                }
                val payload = JSONObject().apply {
                    put("app", "localptt")
                    put("v", 1)
                    put("id", hostId)
                    put("name", hostName)
                    // 优先广播「路由器局域网」地址；直连模式下退到 p2p 地址
                    put("ip", lanIpv4() ?: p2pIpv4() ?: localIpv4() ?: "192.168.49.1")
                    put("ctrl", ctrlPort)
                    put("ts", System.currentTimeMillis())
                }.toString().toByteArray()

                for (addr in broadcastTargets()) {
                    runCatching {
                        socket!!.send(DatagramPacket(payload, payload.size, addr, Discovery.BEACON_PORT))
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w("Discovery", "beacon send failed: ${e.message}")
            }
            Thread.sleep(Discovery.BEACON_INTERVAL_MS)
        }
    }

    private fun registerNsd() {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        this.nsd = nsd
        val info = NsdServiceInfo().apply {
            serviceName = "PTT-$hostName"
            serviceType = Discovery.SERVICE_TYPE
            port = ctrlPort
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w("Discovery", "NSD 注册失败 errorCode=$errorCode")
            }
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                Log.i("Discovery", "NSD 已注册：${serviceInfo?.serviceName}")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
        }
        regListener = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun unregisterNsd() {
        val nsd = this.nsd ?: return
        regListener?.let { runCatching { nsd.unregisterService(it) } }
        regListener = null
    }

    /** NSD/mDNS 与 UDP 组播都依赖多播锁，否则部分机型收不到组播。 */
    private fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        mcLock = runCatching { wifi.createMulticastLock("localptt") }.getOrNull()?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }
}

/**
 * 客户端侧：监听 UDP beacon + NSD 发现，维护「附近主机」列表。
 * [onChange] 在列表变化时回调（线程不定，调用方自行切 UI 线程）。
 */
class DiscoveryListener(private val ctx: Context) {
    private val scope = netScope()
    private val found = ConcurrentHashMap<String, DiscoveredHost>()
    private var socket: DatagramSocket? = null
    private var nsd: NsdManager? = null
    private var mcLock: WifiManager.MulticastLock? = null
    @Volatile private var running = false

    fun start(onChange: (List<DiscoveredHost>) -> Unit) {
        if (running) return
        running = true
        acquireMulticastLock()
        scope.launch { udpReceiveLoop(onChange) }
        scope.launch { pruneLoop(onChange) }
        startNsdDiscovery(onChange)
    }

    /** 当前已发现的主机快照（UI 合并 P2P 设备时用）。 */
    fun currentHosts(): List<DiscoveredHost> = found.values.toList()

    fun stop() {
        running = false
        runCatching { socket?.close() }
        nsd?.let { n -> discoveryListener?.let { runCatching { n.stopServiceDiscovery(it) } } }
        runCatching { mcLock?.release() }; mcLock = null
        scope.cancel()
    }

    private fun udpReceiveLoop(onChange: (List<DiscoveredHost>) -> Unit) {
        val buf = ByteArray(2048)
        while (running) {
            try {
                if (socket == null || socket!!.isClosed) {
                    socket = openUdpSocket(Discovery.BEACON_PORT)?.apply { soTimeout = 1500 }
                    if (socket == null) { Thread.sleep(2000); continue }
                }
                val p = DatagramPacket(buf, buf.size)
                socket!!.receive(p)
                val text = String(p.data, 0, p.length)
                val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (json.optString("app") != "localptt") continue
                val host = DiscoveredHost(
                    id = json.optString("id"),
                    name = json.optString("name").ifEmpty { "未知主机" },
                    ip = json.optString("ip"),
                    ctrlPort = json.optInt("ctrl", 8888),
                    source = "WiFi 广播"
                )
                if (host.ip.isNotEmpty()) {
                    found[host.id] = host
                    onChange(found.values.toList())
                }
            } catch (e: Exception) {
                if (!running) break
                // soTimeout 超时属正常，继续等
            }
        }
    }

    /** 超过 HOST_TIMEOUT_MS 未再收到广播的主机视为下线，从列表剔除。 */
    private suspend fun pruneLoop(onChange: (List<DiscoveredHost>) -> Unit) {
        while (running) {
            delay(1000)
            val now = System.currentTimeMillis()
            val stale = found.values.filter { now - it.lastSeen > Discovery.HOST_TIMEOUT_MS }
            if (stale.isNotEmpty()) {
                stale.forEach { found.remove(it.id) }
                onChange(found.values.toList())
            }
        }
    }

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Suppress("DEPRECATION") // resolveService 在 API 34 弃用；minSdk 24 需保留旧回调 API
    private fun startNsdDiscovery(onChange: (List<DiscoveredHost>) -> Unit) {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        this.nsd = nsd
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i("Discovery", "NSD 发现已启动")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                val s = serviceInfo ?: return
                if (s.serviceType != Discovery.SERVICE_TYPE) return
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.w("Discovery", "NSD 解析失败 errorCode=$errorCode")
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                        val info = serviceInfo ?: return
                        val ip = extractIpv4(info) ?: return
                        val host = DiscoveredHost(
                            id = "nsd_${info.serviceName}",
                            name = info.serviceName?.removePrefix("PTT-")?.ifEmpty { "未知主机" } ?: "未知主机",
                            ip = ip,
                            ctrlPort = info.port,
                            source = "mDNS"
                        )
                        found[host.id] = host
                        onChange(found.values.toList())
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) { /* 由 pruneLoop 超时剔除 */ }
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w("Discovery", "NSD 发现启动失败 errorCode=$errorCode")
                runCatching { nsd.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(Discovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w("Discovery", "discoverServices failed: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    private fun extractIpv4(info: NsdServiceInfo): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
        } else {
            info.host?.hostAddress
        }
    }.getOrNull()

    private fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        mcLock = runCatching { wifi.createMulticastLock("localptt") }.getOrNull()?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }
}
