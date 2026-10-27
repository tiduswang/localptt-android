package com.example.localptt

import com.example.localptt.chat.ChatManager
import com.example.localptt.chat.VoiceFileServer
import com.example.localptt.data.FeatureSync
import com.example.localptt.data.Msg
import com.example.localptt.data.Peer
import com.example.localptt.data.VideoCall
import com.example.localptt.game.GameManager
import com.example.localptt.net.ControlClient
import com.example.localptt.net.ControlServer
import com.example.localptt.net.DiscoveryListener
import com.example.localptt.net.HostBeacon
import com.example.localptt.net.LinkQuality
import com.example.localptt.net.UdpBus
import com.example.localptt.net.WifiDirectManager
import com.example.localptt.net.netGuard
import com.example.localptt.net.uid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * 进程内共享总线：MainActivity 建网后把各模块实例注册到这里，
 * 四个功能页（语音/视频/微聊/游戏）直接取用，避免用 Intent 传递复杂对象。
 * 单进程应用内的最简方案；如需多进程可改为 Service + Binder/AIDL。
 *
 * 注意：myId/myName 由 Application(App) 在进程启动时初始化，
 * 因此任何 Activity 被系统重建时都能安全访问，不会 lateinit 崩溃。
 */
object AppBus {
    var server: ControlServer? = null
    var client: ControlClient? = null
    var bus: UdpBus? = null

    /**
     * 微聊/游戏管理器是**进程内单例**：在 App.onCreate 里创建一次，reset() 时不再置空。
     * 历史 Bug：它们过去在建网时创建、掉线重连时重建，功能页已经订阅的 Flow 指向旧实例，
     * 于是「网络看起来是连着的，但消息/结果永远收不到」。
     */
    var chat: ChatManager? = null
    var game: GameManager? = null
    var voiceServer: VoiceFileServer? = null

    /** 主机侧自动广播（建组后启动，让同网段设备自动发现本机）。 */
    var beacon: HostBeacon? = null

    /** 客户端侧自动发现（打开「加入」页时启动）。 */
    var discovery: DiscoveryListener? = null

    /** Wi-Fi Direct 直连管理器（直连建房/加入时由 RoomManager 持有，收尾时 removeGroup）。 */
    var p2p: WifiDirectManager? = null

    /** 默认空串而非 lateinit：进程重建的极端路径下也不会抛未初始化异常。 */
    var myId: String = ""
    var myName: String = ""

    /** 我的卡通头像 key（见 Avatars），随手 Hello / PeerList 一起同步给对端。 */
    var myAvatar: String = "bear"

    /** 房间名（主机侧显示用）= 我的昵称 + "的房间"。 */
    val roomName: String get() = if (myName.isBlank()) "我的房间" else "$myName 的房间"

    /** 是否主机（建组方）。 */
    val isHost: Boolean get() = server != null

    /**
     * 视频通话信令广播。
     *
     * ★ 用 SharedFlow 而不是 Channel：视频页被重复打开时会有多个收集者，
     *   Channel 单消费者会把 invite/accept 信令交给其中一个，另一个页面就永远接不通。
     */
    private val _videoInbound = MutableSharedFlow<VideoCall>(replay = 8, extraBufferCapacity = 16)
    val videoInbound: SharedFlow<VideoCall> = _videoInbound

    /** 投递一条视频信令给本进程内所有订阅者。 */
    fun postVideo(m: VideoCall) { _videoInbound.tryEmit(m) }

    /**
     * 功能同步事件（同进同出）：由 MainActivity 分发，各功能页订阅。
     *
     * 必须是 **SharedFlow 而不是 Channel**：Channel 是「多个消费者抢同一条队列」，
     * 一条 exit 事件会被 MainActivity 先取走，功能页就永远收不到、无法自动退出。
     * SharedFlow 才是广播给所有订阅者。
     */
    val featureEvents = kotlinx.coroutines.flow.MutableSharedFlow<FeatureSync>(
        replay = 0, extraBufferCapacity = 16
    )

    /** 当前处于前台的功能页（MainActivity 未打开任何功能页时为 null）。 */
    @Volatile var activeFeature: String? = null

    /** 我最近一次主动退出的功能与时间：防止对端的旧状态把我拉回已经退出的页面。 */
    @Volatile var lastExitFeature: String? = null
    @Volatile var lastExitAt: Long = 0L

    /** 对端当前所在的功能页（null = 对端在首页）。由收到的 FeatureSync 更新，功能页据此同步退出。 */
    @Volatile var peerActiveFeature: String? = null

    /**
     * 功能状态心跳：每 2 秒告诉房间里的人「我现在在哪个功能页」（[Feature.STATE]）。
     *
     * 为什么需要它：enter/exit 是**事件**，丢一个包、掉线重连、或者一端先进房间另一端
     * 才冷启动时，两边就会不同步（比如一台还在语音页、另一台已经回首页了）。
     * 状态心跳每 2 秒发一次，对端据此自我校正 —— 这就是「同进同出」的兜底。
     */
    private val featureStateScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + netGuard)
    private var featureStateLoopStarted = false

    fun startFeatureStateSync() {
        if (featureStateLoopStarted) return
        featureStateLoopStarted = true
        featureStateScope.launch {
            while (true) {
                delay(2000)
                runCatching {
                    if (!isConnected) return@runCatching
                    post(
                        FeatureSync(
                            myId, "all", uid(),
                            activeFeature ?: "",
                            com.example.localptt.data.Feature.STATE,
                            LinkQuality.reportable()
                        )
                    )
                }
            }
        }
    }

    /** 最近一次收到的视频邀请方（UI 弹窗用）。 */
    @Volatile var pendingVideoCaller: String? = null

    /**
     * 房间完整名单（含主机和自己），两端由同一份 PeerList 渲染，保证状态一致。
     * 取「对端」请统一用 [others]，不要用 peers.firstOrNull()（可能取到自己）。
     *
     * ★ 用 CopyOnWriteArrayList：名单由网络线程整体替换、UI 线程同时遍历，
     *   普通 ArrayList 在遍历时被改写会抛 ConcurrentModificationException
     *   或让界面卡一下（游戏页尤其明显）。
     */
    val peers: MutableList<Peer> = java.util.concurrent.CopyOnWriteArrayList()

    /** 名单里除自己以外的成员。 */
    val others: List<Peer> get() = peers.filter { it.id != myId }

    /** 是否已建网（有主机身份或客户端连接）。 */
    val isConnected: Boolean get() = server != null || client != null

    /**
     * 信令发送队列（单线程串行，保证消息顺序）。
     * ★ 过去 [post] 直接在调用线程（往往是 UI 线程）里写 socket：
     *   对端接收慢 / TCP 窗口满时 println 会阻塞，游戏按钮就会出现「点了半天没反应」。
     *   现在只入队，真正的写操作在后台线程完成。
     */
    private val sendScope = CoroutineScope(
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ptt-signal-send").apply { isDaemon = true }
        }.asCoroutineDispatcher() + SupervisorJob() + netGuard
    )

    /**
     * 释放并清空全部网络资源。
     * 关键：必须逐个调用各模块的 release/stop，否则旧 socket 仍占用固定端口，
     * 再次建网时 bind 会抛 BindException 导致崩溃（历史闪退主因）。
     */
    fun reset() {
        try { beacon?.stop() } catch (_: Throwable) {}
        beacon = null
        try { discovery?.stop() } catch (_: Throwable) {}
        discovery = null
        try { voiceServer?.stop() } catch (_: Throwable) {}
        voiceServer = null
        try { server?.stop() } catch (_: Throwable) {}
        server = null
        try { client?.disconnect() } catch (_: Throwable) {}
        client = null
        try { bus?.release() } catch (_: Throwable) {}
        bus = null
        // ★ Wi-Fi Direct 组不在这里拆：它由 RoomManager 按「是否直连模式」决定，
        //   否则普通掉线重连时会误删正在使用的直连组。
        // chat / game 是单例，这里**不能**置空：功能页已经订阅了它们的 Flow
        peers.clear()
        // 断网后旧的延迟数字没有意义，清掉，各页面会显示「—」
        LinkQuality.reset()
    }

    /** 发送一条信令：主机走 ControlServer，客户端走 ControlClient（后台串行发送，不卡 UI）。 */
    fun post(m: Msg) {
        sendScope.launch {
            runCatching {
                val c = client
                if (c != null) c.send(m) else server?.send(m)
            }
        }
    }

    /**
     * 广播「我进入了某功能」，并在 1.2s 后补发一次。
     * 补发用于兜底首包丢失 / 对端页面还没起来；重复到达会被对端 openRemotely()
     * 的 activeFeature 去重逻辑挡住，不会反复弹页面。
     */
    fun postFeatureEnter(feature: String) {
        val sync = FeatureSync(myId, "all", uid(), feature, com.example.localptt.data.Feature.ENTER)
        post(sync)
        CoroutineScope(Dispatchers.IO + SupervisorJob() + netGuard).launch {
            delay(1200)
            post(sync)
        }
    }

    /**
     * 广播「我退出了某功能」，并在 0.8s 后补发一次。
     * 与 [postFeatureEnter] 对称：网络抖动丢首包时，补发保证对端能同步退出；
     * 对端已退出则重复到达的 EXIT 会被忽略（页面已销毁，collector 取消）。
     */
    fun postFeatureExit(feature: String) {
        lastExitFeature = feature
        lastExitAt = System.currentTimeMillis()
        val sync = FeatureSync(myId, "all", uid(), feature, com.example.localptt.data.Feature.EXIT)
        post(sync)
        CoroutineScope(Dispatchers.IO + SupervisorJob() + netGuard).launch {
            delay(800)
            post(sync)
        }
    }
}
