package com.example.localptt.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 封装 Wi-Fi Direct（P2P）：一个节点建组成为 Group Owner(GO)，
 * 其余节点发现并连接，从而组成无 AP 的本地 2.4GHz 对讲网络。
 * GO 的固定 IP 通常为 192.168.49.1，作为本应用「主机」地址。
 *
 * 注意：类名不能与 android.net.wifi.p2p.WifiP2pManager 冲突，否则其成员会解析失败。
 *
 * 健壮性：部分设备/模拟器没有 Wi-Fi Direct 硬件，getSystemService 会返回 null，
 * 直接强转或访问会在构造期就 NPE（历史闪退原因之一）。这里全部空安全，
 * P2P 不可用时各方法回调失败，由上层降级到「普通 Wi-Fi 主机/UDP 广播」路径。
 */
class WifiDirectManager(private val ctx: Context) {

    private val mgr: WifiP2pManager? =
        ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private val channel: WifiP2pManager.Channel? = runCatching {
        mgr?.initialize(ctx, Looper.getMainLooper(), null)
    }.getOrNull()

    private val available: Boolean get() = mgr != null && channel != null

    /** 直连发现广播接收器（常驻，直到 [stopDiscovery]）。 */
    private var discoveryReceiver: BroadcastReceiver? = null

    /**
     * 把本机在「Wi-Fi 直连」列表里的名字改成 `PTT-昵称`，方便小伙伴认出来。
     * 仅 Android 10（API 29）及以上支持；旧系统只能显示手机型号。
     *
     * 用反射调用：`setDeviceName` 在部分 SDK 版本上不可直接引用，
     * 直接调用会导致编译期 unresolved；这里失败就静默跳过（只是个易用性增强）。
     */
    fun setDeviceName(name: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val m = mgr ?: return
        val c = channel ?: return
        runCatching {
            val mth = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            mth.invoke(m, c, name.take(32), null)
        }.onFailure { Log.i("LocalPTT", "setDeviceName 不可用：${it.message}") }
    }

    /** 建组（作为主机/GO）。成功后通过回调拿到 GO 信息。 */
    fun createGroup(onResult: (Boolean, WifiP2pInfo?) -> Unit) {
        val m: WifiP2pManager? = mgr
        val c: WifiP2pManager.Channel? = channel
        if (m == null || c == null) {
            Log.w("LocalPTT", "本机不支持 Wi-Fi Direct，改用普通 Wi-Fi 主机模式")
            onResult(false, null)
            return
        }
        runCatching {
            m.createGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = pollConnectionInfo(onResult)
                override fun onFailure(r: Int) {
                    Log.w("LocalPTT", "createGroup 失败 reason=$r（常见：BUSY=旧组未清除）")
                    onResult(false, null)
                }
            })
        }.onFailure {
            Log.w("LocalPTT", "createGroup 调用失败：${it.message}")
            onResult(false, null)
        }
    }

    /**
     * createGroup 的 onSuccess 只代表**下发成功**，P2P 组通常还要几百毫秒才真正成形，
     * 此刻立刻 requestConnectionInfo 常常拿到空 groupOwnerAddress。
     * 这里做有限次轮询，避免误判成失败（表现为「开了直连房间但对端连不进来」）。
     */
    private fun pollConnectionInfo(onResult: (Boolean, WifiP2pInfo?) -> Unit, tries: Int = 10) {
        requestConnectionInfo { ok, info ->
            when {
                ok -> onResult(true, info)
                tries > 0 -> android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ pollConnectionInfo(onResult, tries - 1) }, 400)
                else -> onResult(false, null)
            }
        }
    }

    /** 作为客户端搜索并连接指定 GO。 */
    fun discoverAndConnect(goDevice: WifiP2pDevice, onResult: (Boolean) -> Unit) {
        val m: WifiP2pManager? = mgr
        val c: WifiP2pManager.Channel? = channel
        if (m == null || c == null) { onResult(false); return }
        // 统一用兼容构造器：deviceAddress + groupOwnerIntent = 0（尽量让对方当组长）。
        // 新版 Builder 的「强制 2.4GHz」在部分 SDK 上无法直接引用（编译期 unresolved），
        // 而带外频段并非必需，这里就走全版本通用写法，避免为了一个可选项编译不过。
        @Suppress("DEPRECATION")
        val config = WifiP2pConfig().apply {
            deviceAddress = goDevice.deviceAddress
            groupOwnerIntent = 0
        }
        runCatching {
            m.connect(c, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onResult(true)
                override fun onFailure(r: Int) {
                    Log.w("LocalPTT", "P2P connect 失败 reason=$r")
                    onResult(false)
                }
            })
        }.onFailure {
            Log.w("LocalPTT", "P2P connect 失败：${it.message}")
            onResult(false)
        }
    }

    fun requestConnectionInfo(cb: (Boolean, WifiP2pInfo?) -> Unit) {
        val m: WifiP2pManager? = mgr
        val c: WifiP2pManager.Channel? = channel
        if (m == null || c == null) { cb(false, null); return }
        runCatching {
            m.requestConnectionInfo(c) { info -> cb(info?.groupFormed == true, info) }
        }.onFailure { cb(false, null) }
    }

    /**
     * 等 P2P 组**真正成形**（拿到组长地址）为止。
     *
     * ★ 「直连进不去房间」的主因：`connect()` 的 onSuccess 只代表系统受理了请求，
     *   组通常还要 3~10 秒才成形；过去只 delay(800ms) 就查一次连接信息，
     *   查不到就直接拿 192.168.49.1 去连 TCP —— 此刻对端 p2p 网卡还没起来，必然失败。
     *
     * @return 成形的连接信息；超时返回 null（调用方给出可读提示）。
     */
    suspend fun awaitGroupFormed(timeoutMs: Long = 20_000): WifiP2pInfo? =
        withTimeoutOrNull(timeoutMs) {
            var found: WifiP2pInfo? = null
            while (found == null) {
                val info = suspendCancellableCoroutine<WifiP2pInfo?> { cont ->
                    requestConnectionInfo { _, i -> if (cont.isActive) cont.resume(i) }
                }
                if (info?.groupFormed == true && info.groupOwnerAddress != null) found = info
                else delay(500)
            }
            found
        }

    /** 监听本机 P2P 状态/连接变化（用于 UI 显示与断线重连）。 */
    fun events(): Flow<WifiP2pInfo?> = callbackFlow {
        // 注意：callbackFlow 内有同名的 channel(SendChannel)，这里显式指到外层 P2P 通道
        val m: WifiP2pManager? = mgr
        val c: WifiP2pManager.Channel? = this@WifiDirectManager.channel
        if (m == null || c == null) { close(); return@callbackFlow }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                    requestConnectionInfo { ok, info -> if (ok) trySend(info) }
                }
            }
        }
        runCatching {
            ctx.registerReceiver(
                receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            )
        }.onFailure { close(); return@callbackFlow }
        awaitClose { runCatching { ctx.unregisterReceiver(receiver) } }
    }

    fun removeGroup() {
        val m = mgr ?: return
        val c = channel ?: return
        runCatching { m.removeGroup(c, null) }
    }

    /**
     * 发现附近可连接的 Wi-Fi Direct 设备（持续刷新）。
     * [onPeers] 每次设备列表变化时回调；[onError] 在发现启动失败时回调。
     *
     * 与旧实现的区别：接收器**常驻**，因此列表会跟着附近设备变化自动更新；
     * 弹窗关闭时调用 [stopDiscovery] 释放，不会留下悬挂的广播接收器。
     */
    fun discoverPeers(
        onPeers: (List<WifiP2pDevice>) -> Unit,
        onError: (String) -> Unit
    ) {
        val m: WifiP2pManager? = mgr
        val c: WifiP2pManager.Channel? = channel
        if (m == null || c == null) {
            onError("本机不支持 Wi-Fi Direct")
            return
        }
        stopDiscovery()
        var errorFired = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        m.requestPeers(c) { list: WifiP2pDeviceList ->
                            val usable = list.deviceList.filter {
                                it.status == WifiP2pDevice.AVAILABLE ||
                                    it.status == WifiP2pDevice.INVITED
                            }
                            onPeers(usable)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled && !errorFired) {
                            errorFired = true
                            onError("Wi-Fi Direct 未开启")
                        }
                    }
                }
            }
        }
        discoveryReceiver = receiver
        runCatching {
            ctx.registerReceiver(receiver, IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            })
        }.onFailure {
            discoveryReceiver = null
            onError("注册广播接收器失败")
            return
        }

        runCatching {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { /* 结果经 PEERS_CHANGED 广播回来 */ }
                override fun onFailure(reason: Int) {
                    stopDiscovery()
                    if (!errorFired) {
                        errorFired = true
                        onError(describeDiscoverError(reason))
                    }
                }
            })
        }.onFailure {
            stopDiscovery()
            onError("P2P 发现不可用")
        }
    }

    /** 停止持续发现（同时注销广播接收器）。 */
    fun stopDiscovery() {
        discoveryReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        discoveryReceiver = null
    }

    private fun describeDiscoverError(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "系统繁忙，请稍后重试"
        WifiP2pManager.ERROR -> "内部错误"
        else -> "未知错误($reason)"
    }
}
