package com.example.localptt.room

import android.content.Context
import android.util.Log
import com.example.localptt.AppBus
import com.example.localptt.chat.ChatManager
import com.example.localptt.chat.VoiceFileServer
import com.example.localptt.data.ChatMsg
import com.example.localptt.data.FeatureSync
import com.example.localptt.data.GameCommit
import com.example.localptt.data.GameInvite
import com.example.localptt.data.GameMove
import com.example.localptt.data.GameResult
import com.example.localptt.data.Msg
import com.example.localptt.data.Peer
import com.example.localptt.data.PeerList
import com.example.localptt.data.VideoCall
import com.example.localptt.data.VoiceMeta
import com.example.localptt.game.GameManager
import com.example.localptt.net.ControlClient
import com.example.localptt.net.ControlServer
import com.example.localptt.net.HostBeacon
import com.example.localptt.net.UdpBus
import com.example.localptt.net.WifiDirectManager
import com.example.localptt.net.WifiGate
import com.example.localptt.net.isP2pAddress
import com.example.localptt.net.lanIpv4
import com.example.localptt.net.localIpv4
import com.example.localptt.net.netGuard
import com.example.localptt.net.p2pIpv4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** 房间生命周期状态。UI 只认这 6 个状态，不再有「隐形忙标志」。 */
enum class RoomState {
    /** 未连接：可以开房间或找小伙伴。 */
    IDLE,

    /** 正在开房间（一定会在超时前结束，不会卡住）。 */
    CREATING,

    /** 房间里（我开的对讲房间）。 */
    HOSTING,

    /** 正在加入（同上，一定会在超时前结束）。 */
    JOINING,

    /** 房间里（我加入的对讲房间）。 */
    JOINED,

    /** 正在收尾清理。 */
    CLOSING;

    /** 中间态：正在做事（UI 据此禁用按钮，但用户随时可点「重新开始」打断）。 */
    val busy: Boolean get() = this == CREATING || this == JOINING || this == CLOSING

    /** 已在房间里（开了房或已加入）。 */
    val inRoom: Boolean get() = this == HOSTING || this == JOINED
}

/**
 * 互联方式（UI 用来解释「我是靠什么连上的」，也决定收尾时要不要拆 P2P 组）。
 *
 * - [LAN]    两台手机连在同一个路由器 / 热点下：UDP 广播 + mDNS 自动发现；
 * - [DIRECT] 没有路由器：用 Wi-Fi Direct 自组网（组长地址通常 192.168.49.1）。
 */
enum class ConnectMode {
    NONE,
    LAN,
    DIRECT
}

/**
 * 房间生命周期的唯一权威（进程级单例）。
 *
 * ★ 为什么要有这一层（历史 Bug：「点了开房间后一直显示正在处理中」）
 *   过去「忙碌」是由 MainActivity 里一个手写布尔量 `working` 表达的：
 *   - 它由 `becomeHost()` / `joinHost()` 置 true，**只在网络回调里**置回 false；
 *   - 而 TCP 建连 `Socket(host, port)` 没有超时，目标不可达时会阻塞几分钟；
 *     期间任何点击都只会得到一句「正在处理中，请稍候」——看起来就是 App 卡死了；
 *   - 更糟的是这个标志属于 Activity：页面被回收/重建时逻辑就断在中间，没人负责收尾。
 *
 *   现在改为**显式状态机**：
 *   1. 状态唯一来源：[state] / [status] / [peers]（StateFlow），UI 只做渲染，不做判断；
 *   2. 每个耗时操作都走 [runExclusive]：**互斥 + withTimeout 硬超时 + generation 失效**，
 *      无论底层回调来不来，最多 UI_TIMEOUT_MS 后一定离开中间态；
 *   3. 生命周期属于**进程**而不是 Activity：MainActivity 销毁（旋转、被回收、打开别的页）
 *      不影响房间，信令分发协程也跟着进程活而不是跟着页面活。
 */
object RoomManager {

    private const val TAG = "LocalPTT"

    /** 单个操作的最长等待：超时后强制回到可用状态，绝不让用户干等。 */
    const val CREATE_TIMEOUT_MS = 25_000L   // 含 Wi-Fi Direct 建组（通常 3~8s）
    const val JOIN_TIMEOUT_MS = 15_000L
    const val P2P_TIMEOUT_MS = 14_000L

    /** 掉线后自动重连次数上限。 */
    private const val MAX_AUTO_RETRY = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + netGuard)

    /** 同一时刻只允许一个建网/加入操作，避免端口互相抢导致 consecutive 失败。 */
    private val lock = Mutex()

    /** 操作代号：每次 forceReset() 都会自增，让所有在途回调自动作废。 */
    private val generation = AtomicInteger(0)

    private val _state = MutableStateFlow(RoomState.IDLE)
    val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _status = MutableStateFlow("还没有连上小伙伴哦")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    /** 当前互联方式（路由器局域网 / Wi-Fi Direct）。 */
    private val _mode = MutableStateFlow(ConnectMode.NONE)
    val mode: StateFlow<ConnectMode> = _mode.asStateFlow()

    /** 信令分发协程（进程级，跟随 RoomManager 而非某个页面）。 */
    private var dispatchJob: Job? = null

    /** 自动重连计数（换成房间成功后清零）。 */
    @Volatile private var autoRetry = 0

    // ---------- 对外操作 ----------

    /**
     * 开房：
     *  - 有路由器局域网（同一 Wi-Fi）→ 普通主机模式，UDP 广播/mDNS 自动被发现；
     *  - 没有路由器 → 自动建 Wi-Fi Direct 组，让另一台手机直连过来。
     */
    fun createRoom(app: Context) {
        val ctx = app.applicationContext
        scope.launch {
            runExclusive(RoomState.CREATING) { doCreateRoom(ctx) }
        }
    }

    /**
     * 加入房间。IP 可以是普通局域网地址，也可以是 Wi-Fi Direct 组长的 192.168.49.1。
     * @param viaP2p true 表示刚由 Wi-Fi Direct 连上组长，收尾时不能拆掉这个组。
     */
    fun joinRoom(
        app: Context,
        ip: String,
        port: Int = 8888,
        name: String = "",
        remember: Boolean = true,
        viaP2p: Boolean = false
    ) {
        if (ip.isBlank()) {
            _status.value = "😵 还不知道房间在哪儿"
            return
        }
        val ctx = app.applicationContext
        scope.launch {
            runExclusive(RoomState.JOINING) { doJoinRoom(ctx, ip.trim(), port, name, remember, viaP2p) }
        }
    }

    /**
     * 强制重置（对应 UI 上的「🚪 重新开始」）。
     * 不等互斥锁、不等网络回调：先作废在途回调，再直接清资源，用户立刻拿到可操作界面。
     */
    fun forceReset() {
        generation.incrementAndGet()
        dispatchJob?.cancel()
        autoRetry = 0
        scope.launch {
            runCatching { cleanup() }
            withContext(Dispatchers.Main.immediate) {
                _peers.value = emptyList()
                _state.value = RoomState.IDLE
                _status.value = "已经重新开始啦，点「🏠 开个房间」再来一次 😊"
            }
        }
    }

    /** 正常退出房间（用户主动收摊）。 */
    fun leave() {
        generation.incrementAndGet()
        dispatchJob?.cancel()
        autoRetry = 0
        scope.launch {
            runCatching { cleanup() }
            withContext(Dispatchers.Main.immediate) {
                _peers.value = emptyList()
                _state.value = RoomState.IDLE
                _status.value = "已经离开房间啦，点「🏠 开个房间」可以再开一间"
            }
        }
    }

    /** 记住过的房间地址（用于下次打开 App 自动回房）。 */
    fun rememberedRoom(app: Context): Pair<String, Int>? {
        val raw = prefs(app).getString(KEY_LAST_HOST, null) ?: return null
        val parts = raw.split(":")
        val ip = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8888
        return ip to port
    }

    fun forgetRoom(app: Context) = prefs(app).edit().remove(KEY_LAST_HOST).apply()

    /** 改过昵称/头像后，让全房间立刻看到新形象（不用重新组网）。 */
    fun announceProfile() {
        runCatching { AppBus.server?.announce() }
        runCatching { AppBus.client?.updateProfile(AppBus.myName, AppBus.myAvatar) }
    }

    /**
     * 外部给用户的提示（如 Wi-Fi 被关掉），**不改变房间状态**，只更新状态文案。
     * 与 setState 类操作分开，避免监听类的旁支逻辑把房间状态改坏。
     */
    fun hint(msg: String) {
        scope.launch { setStatus(msg) }
    }

    // ---------- 操作骨架：互斥 + 硬超时 + 失效 ----------

    /**
     * 串行执行一个中间态操作，并保证**一定会退出中间态**。
     *
     * @param busy 进入时的中间态
     * @param block 具体操作体（内部每段挂起后需自检 generation，见 addGenerationCheck）
     */
    private suspend inline fun runExclusive(busy: RoomState, crossinline block: suspend () -> Unit) {
        if (!lock.tryLock()) {
            // 有操作在跑时**不排队**（排队会让用户点不出反应），直接给出可行动的提示
            Log.w(TAG, "${busy} 被忽略：已有操作在进行")
            setStatus("⏳ 刚才那一步还没做完。想立刻重来，点一下「🚪 重新开始」")
            return
        }
        _state.value = busy
        try {
            withTimeout(timeoutOf(busy)) { block() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$busy 超时")
            onOpFailed(busy, IllegalStateException("这一步超时了"))
        } catch (e: Throwable) {
            Log.w(TAG, "$busy 失败：${e.message}")
            onOpFailed(busy, e)
        } finally {
            // 兜底：若 block 提前 return 却没改状态（例如被 generation 作废），必须离开中间态
            if (_state.value == busy) {
                runCatching { cleanup() }
                setPeers(emptyList())
                _state.value = RoomState.IDLE
            }
            lock.unlock()
        }
    }

    private fun timeoutOf(busy: RoomState) = when (busy) {
        RoomState.CREATING -> CREATE_TIMEOUT_MS
        RoomState.JOINING -> JOIN_TIMEOUT_MS
        else -> CREATE_TIMEOUT_MS
    }

    /** 操作失败：清资源回 IDLE，并给出小朋友也能看懂的提示。 */
    private suspend fun onOpFailed(busy: RoomState, e: Throwable) {
        runCatching { cleanup() }
        setPeers(emptyList())
        _state.value = RoomState.IDLE
        val reason = e.message?.takeIf { it.isNotBlank() } ?: "不知道出了什么事"
        _status.value = when (busy) {
            RoomState.CREATING -> "😵 房间没开起来：$reason\n再点一次，或者点「🚪 重新开始」"
            RoomState.JOINING -> "😵 进不去房间：$reason\n确认小伙伴已经点了「🏠 开个房间」并且连同一个 Wi-Fi"
            else -> "😵 $reason，点「🚪 重新开始」再来一次"
        }
    }

    // ---------- 具体实现 ----------

    private suspend fun doCreateRoom(app: Context) {
        val g = generation.incrementAndGet()
        runCatching { cleanup() }

        // ★ Wi-Fi 门禁：只要求「Wi-Fi 开关打开」。没连路由器也能走 Wi-Fi Direct 直连，
        //   所以这里不再因为 NO_LAN 就拒绝开房（旧版正是因此让直连形同虚设）。
        if (!ensureWifiPowered(app)) {
            throw IllegalStateException("Wi-Fi 还没打开，先在手机上打开它再来哦")
        }

        // 有路由器局域网 → 普通主机模式；没有 → 直接建 Wi-Fi Direct 组。
        val lanIp = withContext(Dispatchers.IO) { lanIpv4() }
        val useDirect = lanIp == null

        // 所有 socket 操作都放 IO 线程：过去在 UI 线程 bind ServerSocket，
        // 三星等机型上会明显卡顿甚至 ANR，看起来就是「点了没反应」。
        val bus = withContext(Dispatchers.IO) {
            UdpBus(AppBus.myId).apply { startReceiving() }
        }
        val server = withContext(Dispatchers.IO) {
            ControlServer(
                AppBus.myId, AppBus.myName, AppBus.roomName, AppBus.myAvatar, bus.actualPort
            ).also {
                if (!it.start()) {
                    bus.release()
                    throw IllegalStateException("端口 8888 起都被占用了")
                }
            }
        }
        if (generation.get() != g) {
            closeAll(server, bus, null, null)
            return
        }

        var p2pMgr: WifiDirectManager? = null
        var selfIp: String? = lanIp
        if (useDirect) {
            setStatus("📡 没有路由器，正在开 Wi-Fi 直连…")
            val group = openP2pGroup(app)
            if (group == null) {
                closeAll(server, bus, null, null)
                throw IllegalStateException(
                    "开不了 Wi-Fi 直连：检查 Wi-Fi 是否打开、定位/附近设备权限是否允许"
                )
            }
            p2pMgr = group.first
            selfIp = group.second
        }
        if (generation.get() != g) {
            closeAll(server, bus, null, null)
            runCatching { p2pMgr?.removeGroup() }
            return
        }

        // 资源先用局部变量收着：只有确认本次操作没被「重新开始」作废，才挂到 AppBus。
        // 否则半路被 forceReset 抢先，这些 socket 就会变成没人能关的孤儿。
        val voice = withContext(Dispatchers.IO) {
            VoiceFileServer(File(app.cacheDir, "voice").apply { mkdirs() }).apply { start() }
        }
        val beacon = withContext(Dispatchers.IO) {
            HostBeacon(app, AppBus.myId, AppBus.roomName, server.actualPort).apply { start() }
        }
        if (generation.get() != g) {
            closeAll(server, bus, voice, beacon)
            runCatching { p2pMgr?.removeGroup() }
            return
        }

        val ip = selfIp ?: "127.0.0.1"
        val port = server.actualPort
        server.advertisedIp = ip

        AppBus.server = server
        AppBus.bus = bus
        AppBus.voiceServer = voice
        AppBus.beacon = beacon
        AppBus.p2p = p2pMgr
        ensureSingletons(app)

        AppBus.peers.clear()
        AppBus.peers += Peer(AppBus.myId, AppBus.myName, ip, "host", AppBus.myAvatar, bus.actualPort)
        setPeers(AppBus.peers.toList())

        if (generation.get() != g) return      // 期间被「重新开始」打断 → 不接管 UI 状态
        autoRetry = 0
        _mode.value = if (useDirect) ConnectMode.DIRECT else ConnectMode.LAN
        _state.value = RoomState.HOSTING
        _status.value = if (useDirect) {
            "📡 直连房间开好啦！\n小伙伴点「🔍 找小伙伴」，在「Wi-Fi 直连」里点「PTT-${AppBus.myName}」"
        } else {
            "🏠 ${AppBus.roomName} 开好啦！\n🌐 同一个 Wi-Fi：我的地址 $ip:$port，" +
                "小伙伴点「🔍 找小伙伴」就能进来"
        }
        startDispatch()
        Log.i(TAG, if (useDirect) "Wi-Fi Direct 直连模式，组长地址 $ip" else "路由器局域网模式，本机 $ip")
    }

    private suspend fun doJoinRoom(
        app: Context,
        ip: String,
        port: Int,
        name: String,
        remember: Boolean,
        viaP2p: Boolean
    ) {
        val g = generation.incrementAndGet()
        // 走 Wi-Fi Direct 进来时，组是 MainActivity 刚刚建好的，这里绝不能拆。
        runCatching { cleanup(skipP2p = viaP2p) }

        // ★ Wi-Fi 门禁：没 Wi-Fi 时 TCP 建连只会白等超时（历史上「一直显示正在处理中」
        //   的一大来源就是这个）。先尽力自动打开，打不开就立刻说清楚。
        if (!ensureWifiPowered(app)) {
            throw IllegalStateException("Wi-Fi 还没打开，连不上小伙伴哦")
        }

        val bus = withContext(Dispatchers.IO) {
            UdpBus(AppBus.myId).apply { startReceiving() }
        }
        val client = withContext(Dispatchers.IO) {
            ControlClient(ip, AppBus.myId, AppBus.myName, AppBus.myAvatar, port, bus.actualPort)
        }
        val voice = withContext(Dispatchers.IO) {
            VoiceFileServer(File(app.cacheDir, "voice").apply { mkdirs() }).apply { start() }
        }
        if (generation.get() != g) {
            runCatching { client.disconnect() }
            closeAll(null, bus, voice, null)
            return
        }
        AppBus.client = client
        AppBus.bus = bus
        AppBus.voiceServer = voice
        ensureSingletons(app)

        val label = name.ifBlank { ip }
        val direct = viaP2p || isP2pAddress(ip)
        if (direct) {
            _mode.value = ConnectMode.DIRECT
            setStatus("🔍 正在通过 Wi-Fi 直连进 $label 的房间…")
        } else {
            _mode.value = ConnectMode.LAN
            setStatus("🔍 正在进 $label 的房间…")
        }

        val confirmed = CompletableDeferred<Boolean>()
        client.connect(
            onReady = { ok -> if (generation.get() == g) confirmed.complete(ok) },
            onLost = { if (generation.get() == g) scope.launch { handleLost(app) } },
            // 把「卡在哪一步」直接显示给用户：一直停在连接 → 网络层不通；
            // 变成「等主机确认」→ TCP 已通，问题在主机侧/协议侧。
            onPhase = { phase ->
                if (generation.get() == g) {
                    scope.launch {
                        when (phase) {
                            "connecting" -> setStatus(
                                "⏳ 正在连接 $ip:$port（${if (direct) "Wi-Fi 直连" else "同一个 Wi-Fi"}）…"
                            )
                            "connected" -> setStatus("📨 已经连上 $ip，正在等主机确认…")
                        }
                    }
                }
            }
        )

        val ok = withTimeoutOrNull(JOIN_TIMEOUT_MS) { confirmed.await() } ?: false
        if (generation.get() != g) return
        if (!ok) {
            runCatching { client.disconnect() }
            AppBus.client = null
            throw IllegalStateException(
                "$label 没有回应（连不上 $ip:$port）\n" + if (direct)
                    "确认开房的那台手机还在「房间里」，然后再点一次"
                else
                    "如果是同一个 Wi-Fi 还连不上，可能路由器开了「AP 隔离」，可改用「Wi-Fi 直连」"
            )
        }

        if (remember) prefs(app).edit().putString(KEY_LAST_HOST, "$ip:$port").apply()
        autoRetry = 0
        val selfIp = withContext(Dispatchers.IO) {
            if (direct) p2pIpv4() ?: localIpv4() else lanIpv4() ?: localIpv4()
        } ?: ""
        AppBus.peers.clear()
        AppBus.peers += Peer(AppBus.myId, AppBus.myName, selfIp, "client", AppBus.myAvatar, bus.actualPort)
        setPeers(AppBus.peers.toList())

        if (generation.get() != g) return      // 期间被「重新开始」打断 → 不接管 UI 状态
        _state.value = RoomState.JOINED
        setStatus(
            if (direct) "🎉 直连成功！正在等 $label 发名单…"
            else "🎉 进房间啦！正在等 $label 发名单…"
        )
        startDispatch()
    }

    /**
     * 门禁只检查「Wi-Fi 开关」：路由器局域网与 Wi-Fi Direct 都依赖它，
     * 但「开了 Wi-Fi 却没连路由器」并不影响直连，所以不再要求 [WifiGate.isReady]。
     */
    private suspend fun ensureWifiPowered(app: Context): Boolean {
        if (WifiGate.isWifiOn(app)) return true
        setStatus("📶 正在等 Wi-Fi 打开…")
        WifiGate.tryEnable(app)
        return WifiGate.awaitWifiOn(app, 8_000)
    }

    /**
     * 无可用的路由器局域网时，用 Wi-Fi Direct 自组网（我是组长）。
     * ★ 先 removeGroup 再 createGroup：残留的旧 P2P 组会让 createGroup 一直返回 BUSY，
     *   回调可能永远不来 —— 这是同类开源实现里最常见的「卡住」根因。
     *
     * @return (管理器, 组长地址)；失败返回 null，由调用方给出可读错误。
     */
    private suspend fun openP2pGroup(app: Context): Pair<WifiDirectManager, String>? {
        val mgr = runCatching { WifiDirectManager(app) }.getOrNull() ?: return null
        // 让对端在「Wi-Fi 直连」列表里看到 PTT-昵称（Android 10+）
        runCatching { mgr.setDeviceName("PTT-${AppBus.myName}") }
        val goAddress: InetAddress? = withTimeoutOrNull(P2P_TIMEOUT_MS) {
            runCatching { mgr.removeGroup() }
            delay(600)  // 给系统一点时间释放旧组
            suspendCancellableCoroutine<InetAddress?> { cont ->
                var resumed = false
                runCatching {
                    mgr.createGroup { ok, info ->
                        if (!resumed) {
                            resumed = true
                            cont.resume(if (ok) info?.groupOwnerAddress else null)
                        }
                    }
                }.onFailure {
                    if (!resumed) { resumed = true; cont.resume(null) }
                }
            }
        }
        if (goAddress == null) {
            runCatching { mgr.removeGroup() }
            Log.w(TAG, "Wi-Fi Direct 建组失败")
            return null
        }
        return mgr to (goAddress.hostAddress ?: "192.168.49.1")
    }

    /** 已连接后又断开：有限次数自动重连，超过次数交回给用户手动点。 */
    private suspend fun handleLost(app: Context) {
        if (!_state.value.inRoom) return
        AppBus.peers.clear()
        setPeers(emptyList())
        val saved = rememberedRoom(app)
        if (saved == null) {
            setStatus("😢 和小伙伴走散了，点「🔍 找小伙伴」重新连接")
            return
        }
        if (autoRetry >= MAX_AUTO_RETRY) {
            // 反复重连不上，多半是房间里已经没有主机了：忘掉它，别让下次打开又卡在自动重连
            forgetRoom(app)
            runCatching { cleanup() }
            forceIdle("😵 房间可能已经散了，点「🏠 开个房间」自己开一间吧")
            return
        }
        autoRetry++
        setStatus("😢 和小伙伴走散了，正在重新连…（第 $autoRetry 次）")
        delay(1500)
        val (ip, port) = saved
        // 直连模式保留 P2P 组，重连仍然走组长地址
        joinRoom(app, ip, port, remember = false, viaP2p = _mode.value == ConnectMode.DIRECT)
    }

    private suspend fun forceIdle(msg: String) {
        _state.value = RoomState.IDLE
        setPeers(emptyList())
        setStatus(msg)
    }

    // ---------- 资源与信令 ----------

    /**
     * 释放全部网络资源（chat/game 是进程内单例，这里不清，功能页订阅的 Flow 才不会失效）。
     * @param skipP2p true = 保留当前 Wi-Fi Direct 组（刚连上组长、正要进房间时用）。
     */
    private suspend fun cleanup(skipP2p: Boolean = false) {
        withContext(Dispatchers.IO) {
            runCatching { AppBus.reset() }
            if (!skipP2p) {
                runCatching { AppBus.p2p?.removeGroup() }
                AppBus.p2p = null
            }
            AppBus.peers.clear()
        }
        if (!skipP2p) _mode.value = ConnectMode.NONE
    }

    /** 关闭本次操作已经建出来的资源（还没挂到 AppBus 上的那些）。 */
    private fun closeAll(
        server: ControlServer?,
        bus: UdpBus?,
        voice: VoiceFileServer?,
        beacon: HostBeacon?
    ) {
        runCatching { beacon?.stop() }
        runCatching { voice?.stop() }
        runCatching { bus?.release() }
        runCatching { server?.stop() }
    }

    private fun ensureSingletons(app: Context) {
        if (AppBus.chat == null) AppBus.chat = ChatManager(app.applicationContext)
        if (AppBus.game == null) AppBus.game = GameManager(AppBus.myId)
    }

    /**
     * 启动信令分发（进程级：MainActivity 被回收也不会丢消息）。
     * 过去它挂在 Activity 的 lifecycleScope 上，页面一重建就出现
     * 「网络还连着，但消息/结果收不到」。
     */
    private fun startDispatch() {
        dispatchJob?.cancel()
        val feed = AppBus.client?.inbound ?: AppBus.server?.inbound
        dispatchJob = scope.launch {
            runCatching {
                feed?.receiveAsFlow()?.collect { msg -> dispatch(msg) }
            }
        }
    }

    /** 统一信令分发：名单更新在线表，其余按类型投递给对应模块。 */
    private suspend fun dispatch(msg: Msg) {
        try {
            when (msg) {
                is PeerList -> {
                    AppBus.peers.clear()
                    AppBus.peers.addAll(msg.peers)
                    // 语音目标整表替换（带上每台设备的实际 UDP 端口），成员离开不会残留
                    AppBus.bus?.setTargets(
                        msg.peers.asSequence()
                            .filter { it.id != AppBus.myId && it.ip.isNotBlank() }
                            .map { it.ip to it.voicePort }
                            .toList()
                    )
                    setPeers(msg.peers)
                    updateRoomStatusFromPeers(msg.peers)
                }
                is ChatMsg, is VoiceMeta -> AppBus.chat?.onMessage(msg)
                is GameInvite, is GameCommit, is GameMove, is GameResult -> AppBus.game?.onMessage(msg)
                is VideoCall -> AppBus.postVideo(msg)
                is FeatureSync -> AppBus.featureEvents.tryEmit(msg)
                else -> Unit
            }
        } catch (e: Throwable) {
            Log.w(TAG, "信令分发异常：${e.message}")
        }
    }

    /** 名单变化时同步刷新状态文案，两端措辞不同但信息一致。 */
    private suspend fun updateRoomStatusFromPeers(list: List<Peer>) {
        val others = list.filter { it.id != AppBus.myId }
        val text = when {
            _state.value == RoomState.HOSTING && others.isEmpty() ->
                "🏠 ${AppBus.roomName} 开好啦，等小伙伴进来…"
            _state.value == RoomState.HOSTING ->
                "🎉 ${others.size} 位小伙伴在房间里：" + others.joinToString("、") { it.name }
            others.isEmpty() ->
                "🕐 房间里还没有别的小伙伴"
            else ->
                "🎉 已和 ${others.size} 位小伙伴连上啦：" + others.joinToString("、") { it.name }
        }
        setStatus(text)
    }

    // ---------- 小工具 ----------

    private suspend fun setPeers(list: List<Peer>) =
        withContext(Dispatchers.Main.immediate) { _peers.value = list }

    private suspend fun setStatus(text: String) =
        withContext(Dispatchers.Main.immediate) { _status.value = text }

    private fun prefs(app: Context) =
        app.applicationContext.getSharedPreferences("localptt", Context.MODE_PRIVATE)

    private const val KEY_LAST_HOST = "lastHostAddr"
}
