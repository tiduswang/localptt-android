package com.example.localptt.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.MacAddress
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
        runCatching {
            m.connect(c, WifiP2pConfig.Builder()
                .setDeviceAddress(MacAddress.fromString(goDevice.deviceAddress))
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) // 强制 2.4GHz
                .build(), object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onResult(true)
                override fun onFailure(r: Int) = onResult(false)
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
     * 发现附近可连接的 Wi-Fi Direct 设备。
     * [onPeers] 在拿到设备列表时回调；[onError] 在发现启动失败时回调（reason 为可读原因）。
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
        var delivered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        m.requestPeers(channel) { list: WifiP2pDeviceList ->
                            if (!delivered) {
                                delivered = true
                                onPeers(list.deviceList.toList())
                                runCatching { ctx.unregisterReceiver(this) }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled && !delivered) {
                            delivered = true
                            onError("Wi-Fi Direct 未开启")
                            runCatching { ctx.unregisterReceiver(this) }
                        }
                    }
                }
            }
        }
        runCatching {
            ctx.registerReceiver(receiver, IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            })
        }.onFailure { onError("注册广播接收器失败"); return }

        runCatching {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { /* 结果经 PEERS_CHANGED 广播回来 */ }
                override fun onFailure(reason: Int) {
                    runCatching { ctx.unregisterReceiver(receiver) }
                    if (!delivered) {
                        delivered = true
                        onError(describeDiscoverError(reason))
                    }
                }
            })
        }.onFailure {
            runCatching { ctx.unregisterReceiver(receiver) }
            onError("P2P 发现不可用")
        }
    }

    private fun describeDiscoverError(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "系统繁忙，请稍后重试"
        WifiP2pManager.ERROR -> "内部错误"
        else -> "未知错误($reason)"
    }
}
