package com.example.localptt.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.localptt.AppBus
import com.example.localptt.Profile
import com.example.localptt.R
import com.example.localptt.data.*
import com.example.localptt.net.DiscoveredHost
import com.example.localptt.net.DiscoveryListener
import com.example.localptt.net.WifiDirectManager
import com.example.localptt.net.WifiGate
import com.example.localptt.room.ConnectMode
import com.example.localptt.room.RoomManager
import com.example.localptt.room.RoomState
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 首页：**纯 UI 层**。
 *
 * 这一层只做三件事：读 [RoomManager] 的状态并渲染、把用户点击翻译成 [RoomManager]
 * 的操作、处理「找小伙伴」这个本地扫描弹窗。所有网络资源的生命周期都由
 * [RoomManager] 持有（进程级），因此：
 *  - 旋转屏幕、页面被系统回收、打开功能页再回来，房间都不会断；
 *  - 不再有「working」这种手写忙标志：状态机保证任何操作最多 15 秒必出结果，
 *    就算真的卡住，用户点一下「🚪 重新开始」也能立刻回到可操作状态。
 *
 * 零配置组网（无需手动输入 IP）：
 *  - 主机开房后自动广播（UDP 广播 + mDNS），同网段设备点「找小伙伴」即出现在列表；
 *  - 「附近的主机」列表合并展示：Wi-Fi 广播 / mDNS / Wi-Fi Direct 三类来源；
 *  - 连过的房间会被记住，下次打开 App 自动回房（每个进程只尝试一次，不反复重试）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var ivMyAvatar: android.widget.ImageView
    private lateinit var tvMyName: TextView

    /** 房间里的小伙伴列表：两端用同一份名单渲染，互联状态一眼可核对。 */
    private lateinit var rvPeers: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvPeersTitle: TextView
    private val peerAdapter = PeerAdapter()

    /** 首页按钮均为「彩色卡片」容器（FrameLayout），统一按 View 处理。 */
    private lateinit var btnHost: View
    private lateinit var btnJoin: View
    private lateinit var btnReset: View
    private lateinit var btnVoice: View
    private lateinit var btnVideo: View
    private lateinit var btnChat: View
    private lateinit var btnGame: View

    /** 身份由 Application(App) 初始化，这里只做兜底，避免出现空 id。 */
    private val myId: String get() = AppBus.myId

    private var p2p: WifiDirectManager? = null

    /** 附近主机弹窗的合并数据源：beacon/mDNS 列表 + Wi-Fi Direct 设备列表。 */
    private val nearbyRows = mutableListOf<DiscoveredHost>()
    private var nearbyAdapter: ArrayAdapter<String>? = null
    private var nearbyDialog: AlertDialog? = null
    private var p2pDevices: List<WifiP2pDevice> = emptyList()

    /** Wi-Fi 提示条（顶部那张橙色小卡）：Wi-Fi 没开时自动出现。 */
    private lateinit var wifiTip: View
    private lateinit var tvWifiTip: TextView
    private lateinit var tvWifiFix: TextView

    /** 当前互联方式小胶囊：🏠 路由器 / 📡 直连。 */
    private lateinit var tvMode: TextView

    /** 因缺少「附近设备 / 定位」权限而暂缓的操作：授权后自动接着做。 */
    private var pendingPermissionAction: (() -> Unit)? = null

    /**
     * Wi-Fi Direct 运行时权限（Android 13+ 是 NEARBY_WIFI_DEVICES，之前是精确定位）。
     * 没有这一步，discoverPeers / createGroup 会直接抛 SecurityException ——
     * 这也是旧版「Wi-Fi 直连形同虚设」的根因。
     */
    private val p2pPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        if (!granted) toast("没给「附近的设备」权限，Wi-Fi 直连可能用不了哦")
        val pending = pendingPermissionAction
        pendingPermissionAction = null
        pending?.invoke()
    }

    /** 每个进程只自动回房一次，避免反复重连把界面压在「处理中」。 */
    private var autoRestoreTried = false

    /**
     * 因「没开 Wi-Fi」而暂缓的操作：用户去打开 Wi-Fi 再回来后自动接着做。
     * 为空表示当前没有待办。
     */
    private var pendingWifiAction: (() -> Unit)? = null

    /** 是否正在等 Wi-Fi 就绪（避免用户反复点触发一堆重复等待）。 */
    @Volatile private var awaitingWifi = false

    /** Wi-Fi 开关变化广播：让首页提示条实时反映真实状态。 */
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            refreshWifiTip()
            // 已经在房间里却把 Wi-Fi 关了 → 明确告诉用户后果，而不是默默掉线
            if (!WifiGate.isReady(this@MainActivity) && RoomManager.state.value.inRoom) {
                RoomManager.hint("📵 Wi-Fi 关掉啦，小伙伴会连不上哦～点上面去打开")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (AppBus.myId.isEmpty()) AppBus.myId = "dev_${deviceId()}"
        if (AppBus.myName.isBlank()) {
            Profile.load(this)
            if (AppBus.myName.isBlank()) AppBus.myName = android.os.Build.MODEL ?: "Android"
        }

        bindViews()
        bindClicks()
        observeRoom()
        refreshWifiTip()
        autoEnableWifiOnLaunch()

        if (intent?.getBooleanExtra("crash", false) == true) {
            Toast.makeText(this, "😅 刚刚打了个喷嚏，再点一次「🏠 开个房间」就好啦", Toast.LENGTH_LONG).show()
        }
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        ivMyAvatar = findViewById(R.id.ivMyAvatar)
        tvMyName = findViewById(R.id.tvMyName)
        rvPeers = findViewById(R.id.rvPeers)
        tvPeersTitle = findViewById(R.id.tvPeersTitle)
        rvPeers.layoutManager = LinearLayoutManager(this)
        rvPeers.adapter = peerAdapter

        btnHost = findViewById(R.id.btnHost)
        btnJoin = findViewById(R.id.btnJoin)
        btnReset = findViewById(R.id.btnReset)
        btnVoice = findViewById(R.id.btnVoice)
        btnVideo = findViewById(R.id.btnVideo)
        btnChat = findViewById(R.id.btnChat)
        btnGame = findViewById(R.id.btnGame)

        wifiTip = findViewById(R.id.wifiTip)
        tvWifiTip = findViewById(R.id.tvWifiTip)
        tvWifiFix = findViewById(R.id.tvWifiFix)
        tvMode = findViewById(R.id.tvMode)
    }

    /**
     * 首页点击绑定。
     *
     * 麦克风/相机仍按需向语音页、视频页要；这里只额外要一个「附近的设备 / 定位」权限 ——
     * Wi-Fi Direct 建组与扫描需要它，且**只在用户点开房/找小伙伴时才要**，
     * 不授权也还能走同一个路由器（UDP 广播 / mDNS）这条路。
     */
    private fun bindClicks() {
        findViewById<View>(R.id.btnProfile).setOnClickListener {
            startActivity(android.content.Intent(this, ProfileActivity::class.java))
        }
        btnHost.setOnClickListener { createRoom() }
        btnJoin.setOnClickListener { joinRoom() }
        findViewById<View>(R.id.btnWifiFix).setOnClickListener {
            // 旧系统能自动打开就直接打开；Android 10+ 拉系统 Wi-Fi 面板
            if (!WifiGate.tryEnable(this)) WifiGate.openWifiUi(this)
            // 若用户已经 pending 过某个操作（比如点过「开个房间」），开好后自动继续
            waitForWifiThenRun()
        }
        btnReset.setOnClickListener {
            RoomManager.forgetRoom(this)
            RoomManager.forceReset()
            autoRestoreTried = true
            toast("已经收好啦，可以重新开始了 😊")
        }

        // 进入任一功能都会广播「同步进入」，房间里的小伙伴会自动进入同一页
        btnVoice.setOnClickListener { openFeature(Feature.VOICE, VoiceActivity::class.java) }
        btnVideo.setOnClickListener { openFeature(Feature.VIDEO, VideoActivity::class.java) }
        btnChat.setOnClickListener { openFeature(Feature.CHAT, ChatActivity::class.java) }
        btnGame.setOnClickListener { openFeature(Feature.GAME, GameActivity::class.java) }
    }

    // ---------- 订阅房间状态（UI = f(state)） ----------

    private fun observeRoom() {
        RoomManager.status
            .onEach { status.text = it }
            .launchIn(lifecycleScope)

        RoomManager.peers
            .onEach { applyPeerList(it) }
            .launchIn(lifecycleScope)

        RoomManager.state
            .onEach { renderForState(it) }
            .launchIn(lifecycleScope)

        RoomManager.mode
            .onEach { renderMode(it) }
            .launchIn(lifecycleScope)

        // 功能同步：一台进入，房间里的小伙伴一起进入
        lifecycleScope.launch {
            AppBus.featureEvents.collect { s ->
                if (s.from != myId && s.action == Feature.ENTER) openRemotely(s.feature)
            }
        }
    }

    /**
     * 状态驱动的按钮外观：中间态只做「变淡」提示，不偷偷禁用逻辑；
     * 功能卡片在没连上房间时变淡，但点击会给出明确指引，方便小朋友理解。
     */
    private fun renderForState(s: RoomState) {
        val busy = s.busy
        btnHost.alpha = if (busy) 0.45f else 1f
        btnJoin.alpha = if (busy) 0.45f else 1f
        btnHost.isEnabled = !busy
        btnJoin.isEnabled = !busy

        val inRoom = s.inRoom
        listOf(btnVoice, btnVideo, btnChat, btnGame).forEach { it.alpha = if (inRoom) 1f else 0.5f }
    }

    /**
     * 互联方式小胶囊：让用户一眼看清「这次是靠路由器还是靠直连」。
     * 也为排障省事 —— 连不上时先看这里是不是和自己以为的方式不一样。
     */
    private fun renderMode(m: ConnectMode) {
        val text = when (m) {
            ConnectMode.LAN -> "🌐 同一个 WiFi"
            ConnectMode.DIRECT -> "📡 WiFi 直连"
            ConnectMode.NONE -> ""
        }
        tvMode.text = text
        tvMode.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        // Wi-Fi 开关变化时立刻刷新首页提示条（动态注册：不需要常驻，也不接收外部来源）
        runCatching {
            ContextCompat.registerReceiver(
                this, wifiReceiver,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到首页：清空「当前功能页」标记，允许再次被对端同步拉起
        AppBus.activeFeature = null
        refreshSelf()
        refreshWifiTip()
        waitForWifiThenRun()   // 用户从系统 Wi-Fi 面板回来 → 自动接着做刚才那一步
        maybeAutoRestore()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(wifiReceiver) }
        super.onStop()
    }

    /** 记住过的房间，进程启动后自动回一次；失败也不再重试，避免用户被困在「处理中」。 */
    private fun maybeAutoRestore() {
        // 正在处理 Wi-Fi 门禁时不要插队，否则两个 join 会互相把对方挡掉
        if (pendingWifiAction != null || awaitingWifi) return
        // 只要求 Wi-Fi 开关打开：没有路由器时上一次的直连房间也可能还在
        if (!WifiGate.isWifiOn(this)) return
        if (autoRestoreTried) return
        if (RoomManager.state.value.inRoom || RoomManager.state.value.busy) return
        val saved = RoomManager.rememberedRoom(this) ?: return
        autoRestoreTried = true
        status.text = "🔍 正在自动回到上次的房间…"
        RoomManager.joinRoom(this, saved.first, saved.second, remember = true)
    }

    // ---------- 功能同步：一台进入，房间里的小伙伴一起进入 ----------

    /**
     * 本地点击进入功能页：广播 enter 信令，对端收到后自动进入同一页。
     *
     * ★ 注意：这里**不要**在 startActivity 之前预置 [AppBus.activeFeature]。
     *   页面还没创建就把标记写好，一旦某处没清干净，对端的 openRemotely()
     *   会被「已经在这个功能里了」误判挡住 → 同步进入失效。标记由功能页自己维护。
     */
    private fun openFeature(feature: String, cls: Class<out android.app.Activity>) {
        if (!RoomManager.state.value.inRoom) {
            toast("先点「🏠 开个房间」或「🔍 找小伙伴」，连上小伙伴才能一起玩哦")
            return
        }
        AppBus.postFeatureEnter(feature)
        startActivity(android.content.Intent(this, cls))
    }

    /** 收到对端的 enter：自动打开同一功能页（已经在里面则不重复打开）。 */
    private fun openRemotely(feature: String) {
        if (AppBus.activeFeature == feature) return
        val cls = when (feature) {
            Feature.VOICE -> VoiceActivity::class.java
            Feature.VIDEO -> VideoActivity::class.java
            Feature.CHAT -> ChatActivity::class.java
            Feature.GAME -> GameActivity::class.java
            else -> return
        }
        AppBus.activeFeature = feature
        runOnUiThread { startActivity(android.content.Intent(this, cls)) }
    }

    // ---------- 建网 / 加入 ----------

    private fun createRoom() {
        when {
            // 已经在里面还点开房：只是刷新名单，不再重复建组（重复建组会把已连进来的小伙伴踢掉）
            RoomManager.state.value == RoomState.HOSTING -> {
                toast("房间已经开好啦，等小伙伴进来就行 😊")
            }
            RoomManager.state.value.busy -> {
                toast("⏳ 正在忙上一步。想立刻重来，点「🚪 重新开始」")
            }
            else -> withP2pPermission { ensureWifi { actuallyCreateRoom() } }
        }
    }

    private fun actuallyCreateRoom() {
        status.text = "🏠 正在开房间…"
        RoomManager.createRoom(this)
    }

    /**
     * 「找小伙伴」：
     *  - 同一个路由器 → UDP 广播 + mDNS 自动发现；
     *  - 没有路由器 → Wi-Fi Direct 扫描附近开好直连房间的设备。
     * 权限被拒也照样把弹窗打开（局域网那条路不需要权限），只是直连设备少一些。
     */
    private fun joinRoom() = ensureWifi { withP2pPermission { showNearbyHostsDialog() } }

    // ---------- Wi-Fi Direct 运行时权限 ----------

    /** 需要的权限：Android 13+ 是「附近的设备」，更早的系统要求精确定位。 */
    private fun p2pPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasP2pPermission(): Boolean = p2pPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 有权限就直接做；没有就先申请，用户无论同意还是拒绝，最后都会继续执行 [then]。
     * （局域网互联不依赖该权限，不该因为拒权就把用户挡在门外。）
     */
    private fun withP2pPermission(then: () -> Unit) {
        if (hasP2pPermission()) { then(); return }
        pendingPermissionAction = then
        runCatching { p2pPermissionLauncher.launch(p2pPermissions()) }
            .onFailure {
                // 极端机型上 launcher 不可用：不阻塞主流程
                pendingPermissionAction = null
                then()
            }
    }

    // ---------- Wi-Fi 门禁：没开就提示，能开就自动开 ----------

    /**
     * 做事之前先确认 **Wi-Fi 开关是打开的**。
     *
     * ★ 这里只检查开关，不要求「连上路由器」：没有路由器时两台手机可以走 Wi-Fi Direct
     *   直连，旧版在这里用 `isReady` 一刀切，直接让直连永远走不到（核心 Bug）。
     *
     * - Android 9 及以下：[WifiGate.tryEnable] 可以**直接替用户打开**，全程无感知；
     * - Android 10 起：系统禁止第三方开关 Wi-Fi，改弹提示 + 拉系统 Wi-Fi 面板，
     *   用户回来后由 [waitForWifiThenRun] 自动继续刚才那一步。
     */
    private fun ensureWifi(then: () -> Unit) {
        if (awaitingWifi) { toast("⏳ 正在等 Wi-Fi 打开…"); return }
        pendingWifiAction = null
        if (WifiGate.isWifiOn(this)) { then(); return }

        pendingWifiAction = then
        if (WifiGate.tryEnable(this)) {
            status.text = "📶 正在帮你打开 Wi-Fi…"
            waitForWifiThenRun()
            return
        }
        promptEnableWifi("Wi-Fi 还是关着的哦")
    }

    /** 童趣提示弹窗：说明为什么要 Wi-Fi，并给一条最短的操作路径。 */
    private fun promptEnableWifi(title: String) {
        AlertDialog.Builder(this)
            .setTitle("📶 $title")
            .setMessage("小伙伴之间要靠 Wi-Fi 才能说话～\n点「去打开」开好之后，会自动接着做刚才那一步 😊")
            .setPositiveButton("📶 去打开") { _, _ -> WifiGate.openWifiUi(this) }
            .setNegativeButton("还是试试") { _, _ ->
                val act = pendingWifiAction
                pendingWifiAction = null
                act?.invoke()
            }
            .show()
        refreshWifiTip()
    }

    /**
     * 等 Wi-Fi 开关真正打开再继续（面板关闭时需轮询，系统打开开关比 UI 慢半拍）。
     * 等待**一定有时间上限**，超时也照样执行：失败信息由 RoomManager 给出，不让用户干等。
     */
    private fun waitForWifiThenRun() {
        if (pendingWifiAction == null || awaitingWifi) return
        awaitingWifi = true
        lifecycleScope.launch {
            val ready = WifiGate.awaitWifiOn(this@MainActivity, 12_000)
            awaitingWifi = false
            val pending = pendingWifiAction ?: return@launch
            pendingWifiAction = null
            refreshWifiTip()
            if (ready) status.text = "📶 Wi-Fi 好啦，继续～"
            else toast("📵 Wi-Fi 好像还没开，可能找不到小伙伴哦")
            pending.invoke()
        }
    }

    /**
     * 刚进 App：Wi-Fi 没开就先悄悄替用户打开。
     * Android 9 及以下 [WifiGate.tryEnable] 真能打开（无声无息，最省事）；
     * Android 10 起系统不允许，这里什么都不做，由提示条引导用户一键打开。
     */
    private fun autoEnableWifiOnLaunch() {
        if (WifiGate.isWifiOn(this)) return
        if (!WifiGate.tryEnable(this)) return
        lifecycleScope.launch {
            WifiGate.awaitWifiOn(this@MainActivity, 8_000)
            refreshWifiTip()
            if (WifiGate.isWifiOn(this@MainActivity)) toast("📶 已经帮你打开 Wi-Fi 啦")
        }
    }

    /**
     * 首页橙色提示条：只在**Wi-Fi 关着**或**没连路由器**时出现。
     * 后者的文案要说清「也能直连」，否则用户会以为自己必须去连一个路由器。
     */
    private fun refreshWifiTip() {
        val st = WifiGate.status(this)
        wifiTip.visibility = if (st == WifiGate.Status.OK) View.GONE else View.VISIBLE
        when (st) {
            WifiGate.Status.WIFI_OFF -> {
                tvWifiTip.text = "Wi-Fi 还没打开哦"
                tvWifiFix.text = "去打开"
            }
            WifiGate.Status.NO_LAN -> {
                tvWifiTip.text = "没连路由器？两台手机也能直连"
                tvWifiFix.text = "连 WiFi"
            }
            WifiGate.Status.OK -> tvWifiTip.text = ""
        }
    }

    /** 打开「附近的主机」实时列表：UDP 广播 + mDNS + Wi-Fi Direct 三类合并。 */
    private fun showNearbyHostsDialog() {
        if (RoomManager.state.value.busy) {
            toast("⏳ 正在忙上一步。想立刻重来，点「🚪 重新开始」")
            return
        }
        nearbyRows.clear()
        val listView = ListView(this)
        nearbyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = nearbyAdapter
        listView.setOnItemClickListener { _, _, i, _ ->
            val row = nearbyRows.getOrNull(i) ?: return@setOnItemClickListener
            nearbyDialog?.dismiss()
            if (row.source == "WiFi Direct" && row.ip.isEmpty()) {
                @Suppress("UNCHECKED_CAST")
                joinP2pPeer(row.extra as? WifiP2pDevice)
            } else {
                RoomManager.joinRoom(this, row.ip, row.ctrlPort, row.name)
            }
        }

        nearbyDialog = AlertDialog.Builder(this)
            .setTitle("🔍 附近的小伙伴（正在找…）")
            .setView(listView)
            .setNegativeButton("⌨️ 手动输入") { _, _ -> showManualIpDialog() }
            .setOnDismissListener {
                nearbyDialog = null
                nearbyAdapter = null
                try { AppBus.discovery?.stop() } catch (_: Throwable) {}
                AppBus.discovery = null
                // 直连扫描的接收器是常驻的，必须在这里显式注销，否则会一直泄漏
                runCatching { p2p?.stopDiscovery() }
            }
            .create()
        nearbyDialog?.show()
        startNearbySearch()
    }

    private fun startNearbySearch() {
        // 1) UDP 广播 + mDNS：持续刷新（不需要任何运行时权限）
        val listener = DiscoveryListener(this)
        AppBus.discovery = listener
        listener.start { list -> runOnUiThread { mergeAndRender(list, p2pDevices) } }

        // 2) Wi-Fi Direct 原生发现：无路由器场景的补充。权限不足/不支持时静默降级，
        //    绝不影响上面两条通道（过去这里失败会让整个「找小伙伴」看起来没反应）。
        if (!hasP2pPermission()) {
            Log.i("LocalPTT", "没有附近设备权限，跳过 Wi-Fi Direct 扫描（局域网发现照常）")
            return
        }
        lifecycleScope.launch {
            val devices = withTimeoutOrNull(10_000) { discoverP2pDevices() }
            if (!devices.isNullOrEmpty()) {
                p2pDevices = devices
                runOnUiThread { mergeAndRender(AppBus.discovery?.currentHosts().orEmpty(), devices) }
            }
        }
    }

    /** 一次性 Wi-Fi Direct 设备发现，带超时，不会挂起 UI。 */
    private suspend fun discoverP2pDevices(): List<WifiP2pDevice>? {
        val mgr = p2p ?: runCatching { WifiDirectManager(this) }.getOrNull() ?: return null
        p2p = mgr
        return suspendCancellableCoroutine<List<WifiP2pDevice>?> { cont ->
            var resumed = false
            fun done(list: List<WifiP2pDevice>?) {
                if (!resumed) { resumed = true; if (cont.isActive) cont.resume(list) }
            }
            runCatching {
                mgr.discoverPeers(
                    onPeers = { done(it) },
                    onError = { reason ->
                        Log.i("LocalPTT", "P2P 发现不可用：$reason（Wi-Fi 广播/mDNS 仍可用）")
                        done(null)
                    }
                )
            }.onFailure { done(null) }
        }
    }

    /**
     * 只有本应用开房时才会把设备名改成 `PTT-昵称`（Android 10+ 才有此能力）。
     *
     * ★ 不做这个过滤的后果：Wi-Fi Direct 扫描列出的是**附近所有**支持直连的设备
     *   （邻居的手机、电视、打印机……），用户点了其中一个只会白等几十秒 —— 这就是
     *   「列表里一堆别的设备名字、点了又进不去」的根因。
     */
    private fun isAppDirectPeer(dev: WifiP2pDevice): Boolean {
        val n = dev.deviceName ?: return false
        return n.trim().startsWith("PTT", ignoreCase = true)
    }

    /** 合并三类来源并刷新列表（Wi-Fi Direct 设备无 IP，需点击后先建 P2P 连接）。 */
    private fun mergeAndRender(beaconHosts: List<DiscoveredHost>, p2pList: List<WifiP2pDevice>) {
        val merged = mutableListOf<DiscoveredHost>()
        p2pList.filter { isAppDirectPeer(it) }.forEach { dev ->
            merged += DiscoveredHost(
                id = "p2p_${dev.deviceAddress}",
                name = dev.deviceName.ifEmpty { dev.deviceAddress },
                ip = "",
                ctrlPort = 8888,
                source = "WiFi Direct",
                extra = dev
            )
        }
        beaconHosts.forEach { h ->
            if (h.ip.isEmpty()) return@forEach
            if (merged.none { it.ip == h.ip }) merged += h
        }
        nearbyRows.clear()
        nearbyRows.addAll(merged.sortedBy { it.name })
        nearbyAdapter?.clear()
        if (merged.isEmpty()) {
            nearbyAdapter?.add(
                "🔍 正在找…还没找到\n" +
                    "• 同一个 Wi-Fi：让小伙伴先点「🏠 开个房间」\n" +
                    "• 没有路由器：对方会在「Wi-Fi 直连」里显示成「PTT-昵称」"
            )
        } else {
            merged.sortedBy { it.name }.forEach { h ->
                val label = if (h.ip.isEmpty()) "📡 Wi-Fi 直连 · 点一下跳过路由器" else "🌐 同一个 Wi-Fi · 点一下进房间"
                nearbyAdapter?.add("${h.name}\n$label")
            }
        }
    }

    private fun showManualIpDialog() {
        val input = EditText(this).apply { setText("192.168.49.1") }
        AlertDialog.Builder(this)
            .setTitle("⌨️ 输入小伙伴的地址")
            .setView(input)
            .setPositiveButton("连接") { _, _ ->
                RoomManager.joinRoom(this, input.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 走 Wi-Fi Direct 直连：先 P2P 连上，拿到组长 IP 再按普通流程进房间。
     *
     * ★ 关键点
     *  1. `viaP2p = true`：RoomManager 收尾时**不能**拆掉这个刚建好的 P2P 组；
     *  2. 如果协商结果自己成了组长（对端没抢到），组长地址是 192.168.49.1，
     *     但房间主机在对端 —— 这时连过去必然失败，直接给出可读提示，避免白等 15 秒；
     *  3. 不把正在发现的接收器留着：连上之后就停掉扫描，省电也避免列表乱跳。
     */
    private fun joinP2pPeer(dev: WifiP2pDevice?) {
        val d = dev ?: return
        lifecycleScope.launch {
            status.text = "🔍 正在直连 ${d.deviceName.ifBlank { d.deviceAddress }} …"
            val mgr = p2p ?: runCatching { WifiDirectManager(this@MainActivity) }.getOrNull()
            if (mgr == null) {
                status.text = "😵 这台手机不支持 Wi-Fi 直连，试试连同一个 Wi-Fi"
                return@launch
            }
            p2p = mgr
            AppBus.p2p = mgr
            runCatching { mgr.stopDiscovery() }

            val connected = withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    fun end(ok: Boolean) { if (cont.isActive) cont.resume(ok) }
                    runCatching { mgr.discoverAndConnect(d) { end(it) } }.onFailure { end(false) }
                }
            } ?: false
            if (!connected) {
                status.text = "😵 直连连不上，再点一次试试；也可以两台手机连同一个 Wi-Fi"
                return@launch
            }

            // ★ 关键：connect() 的 onSuccess 只代表系统受理了请求，P2P 组还要 3~10 秒才成形。
            //   过去这里只等 800ms 就查连接信息，查不到便拿 192.168.49.1 硬连 TCP，
            //   此刻对端 p2p 网卡还没起来 —— 这就是「直连进不去房间」的直接原因。
            status.text = "📡 直连请求已发出，正在等系统建组…"
            val info = mgr.awaitGroupFormed(20_000)
            if (info == null) {
                status.text = "😵 直连组没建起来。请让开房间的那台手机再点一次「🏠 开个房间」"
                runCatching { mgr.removeGroup() }
                AppBus.p2p = null
                return@launch
            }
            if (info.isGroupOwner) {
                // 自己成了组长：说明对端不在 / 协商失败，房间主机不在本机，连进去不可能成功
                status.text = "😵 直连没谈成（自己成了组长）。请让开房间的那台手机再点一次「🏠 开个房间」"
                runCatching { mgr.removeGroup() }
                AppBus.p2p = null
                return@launch
            }
            val ip = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            status.text = "📡 直连成功（组长 $ip），正在进房间…"
            RoomManager.joinRoom(
                this@MainActivity, ip, 8888, d.deviceName, viaP2p = true
            )
        }
    }

    // ---------- 名单渲染 ----------

    /** 收到的名单由 RoomManager 统一推送；这里只负责渲染。 */
    private fun applyPeerList(list: List<Peer>) {
        peerAdapter.submit(list)
        tvPeersTitle.text =
            if (list.isEmpty()) "👫 房间里的小伙伴" else "👫 房间里的小伙伴（${list.size} 位）"
    }

    /** 刷新我的名片（昵称/头像可能在 ProfileActivity 改过）。 */
    private fun refreshSelf() {
        tvMyName.text = AppBus.myName.ifBlank { "小朋友" }
        ivMyAvatar.setImageResource(Avatars.drawableOf(AppBus.myAvatar))
    }

    private fun deviceId(): String {
        val aid = runCatching {
            android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull()
        return (aid ?: "unknown").takeLast(6)
    }

    override fun onDestroy() {
        super.onDestroy()
        // ★ 这里**不能** AppBus.reset()：过去把房间生命周期挂在 Activity 上，
        //   一旦旋转屏幕或 Activity 被系统回收，房间就悄悄没了（也很难排查）。
        //   现在资源由 RoomManager 持有，退到后台或销毁页面都不会断。
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
