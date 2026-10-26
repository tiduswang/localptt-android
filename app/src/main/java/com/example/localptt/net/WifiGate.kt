package com.example.localptt.net

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Wi-Fi 门禁：开房间 / 找小伙伴之前的**第一道检查**。
 *
 * ★ 为什么要有这一层
 *   本 App 的全部通信都建立在本地 Wi-Fi 局域网上。Wi-Fi 关着的时候去组网，
 *   TCP/UDP 一路走超时，用户只会看到「转圈 15 秒后失败」，并不知道是自己忘了开 Wi-Fi。
 *   现在的做法是：**先进门（检查 + 能开就自动开），再做事**。
 *
 * Android 各版本的开关限制：
 *  - Android 9（API 28）及以下：第三方 App 可以用 `WifiManager.setWifiEnabled(true)`
 *    **直接替用户打开**，这是真正意义上的「自动打开」；
 *  - Android 10（API 29）起：系统禁止第三方开关 Wi-Fi，`setWifiEnabled` 只会返回 false。
 *    此时改拉 **系统 Wi-Fi 面板**（Settings.Panel.ACTION_WIFI），用户点一下就开，
 *    回到 App 后自动继续刚才那一步（由调用方通过 [DOING] 轮询实现）。
 */
object WifiGate {

    private const val TAG = "LocalPTT"

    /** Wi-Fi / 局域网可用情况。 */
    enum class Status {
        /** 已在局域网里（Wi-Fi、热点、有线以太网均可），可以正常组网。 */
        OK,

        /** Wi-Fi 开关是关的：必须先把开关打开。 */
        WIFI_OFF,

        /** 开关开着但还没连上任何网络（正在获取 IP / 开了却没连接）：再等等或连一个 Wi-Fi。 */
        NO_LAN
    }

    fun wifiManager(ctx: Context): WifiManager? =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * Wi-Fi 开关是否打开。拿不到 WifiManager 时按「打开」处理——
     * 宁可不拦，**绝不能因为读不到状态就误报没开 Wi-Fi**，那会让用户白折腾一轮。
     */
    fun isWifiOn(ctx: Context): Boolean = runCatching {
        wifiManager(ctx)?.isWifiEnabled ?: true
    }.getOrDefault(true)

    /**
     * 当前可用情况。注意顺序：先看你是不是**已经在一个局域网里**，
     * 有线以太网 / 已开热点的机器即使 Wi-Fi 开关关口也应放行。
     */
    fun status(ctx: Context): Status = when {
        wifiIpv4() != null -> Status.OK
        !isWifiOn(ctx) -> Status.WIFI_OFF
        else -> Status.NO_LAN
    }

    fun isReady(ctx: Context) = status(ctx) == Status.OK

    /**
     * 尝试**直接替用户打开** Wi-Fi（无需跳转界面）。
     * @return true 已经打开或本次调用确实打开了；false 表示系统不允许，需要跳 [openWifiUi]。
     */
    @Suppress("DEPRECATION") // API 29 起废弃：系统禁止第三方调用，但旧系统上这是唯一的自动手段
    fun tryEnable(ctx: Context): Boolean {
        if (isReady(ctx)) return true
        val wm = wifiManager(ctx) ?: return false
        val ok = runCatching { wm.setWifiEnabled(true) }.getOrDefault(false)
        if (ok) Log.i(TAG, "已自动打开 Wi-Fi（系统 API 允许）")
        return ok
    }

    /**
     * 拉起系统的 Wi-Fi 界面让用户打开（Android 10+ 唯一合规方式）。
     * 优先用 Android 10 引入的「悬浮面板」Settings.Panel.ACTION_WIFI：不用跳到完整设置页，
     * 面板里直接就有开关；个别定制 ROM 不支持时逐级降级到完整 Wi-Fi 设置页。
     */
    fun openWifiUi(ctx: Context) {
        val candidates = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= 29) candidates += Intent(Settings.Panel.ACTION_WIFI)
        candidates += Intent(Settings.ACTION_WIFI_SETTINGS)
        candidates += Intent(Settings.ACTION_WIRELESS_SETTINGS)
        for (i in candidates) {
            val ok = runCatching {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                true
            }.getOrDefault(false)
            if (ok) return
        }
        Log.w(TAG, "打不开系统 Wi-Fi 界面，只能请用户自己去设置里开")
    }

    /**
     * 轮询等待局域网就绪。跳过耦（2.4G 路由器 / Wi-Fi Direct 组）需要几秒，
     * 这里给足等待，但**一定有上限**，绝不让用户干等。
     */
    suspend fun awaitReady(ctx: Context, timeoutMs: Long = 12_000): Boolean =
        withContext(Dispatchers.IO) {
            val step = 400L
            var waited = 0L
            while (waited < timeoutMs) {
                if (isReady(ctx)) return@withContext true
                delay(step)
                waited += step
            }
            isReady(ctx)
        }
}
