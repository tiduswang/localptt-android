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
import com.example.localptt.net.UdpBus
import com.example.localptt.net.WifiDirectManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

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

    /** 最近一次收到的视频邀请方（UI 弹窗用）。 */
    @Volatile var pendingVideoCaller: String? = null

    /**
     * 房间完整名单（含主机和自己），两端由同一份 PeerList 渲染，保证状态一致。
     * 取「对端」请统一用 [others]，不要用 peers.firstOrNull()（可能取到自己）。
     */
    val peers: MutableList<Peer> = mutableListOf()

    /** 名单里除自己以外的成员。 */
    val others: List<Peer> get() = peers.filter { it.id != myId }

    /** 是否已建网（有主机身份或客户端连接）。 */
    val isConnected: Boolean get() = server != null || client != null

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
    }

    /** 发送一条信令：主机走 ControlServer，客户端走 ControlClient。 */
    fun post(m: Msg) {
        val c = client
        if (c != null) runCatching { c.send(m) }
        else runCatching { server?.send(m) }
    }
}
