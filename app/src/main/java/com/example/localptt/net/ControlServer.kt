package com.example.localptt.net

import android.util.Log
import com.example.localptt.data.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * 主机侧 TCP 信令服务（默认端口 8888，被占用则递增）。
 *
 * 修复要点（互联状态不一致的历史 Bug）：
 *  1. **名单本地环回**：broadcastPeerList() 过去只发给客户端，主机自己的 inbound 永远收不到
 *     PeerList，导致「客户端显示已连接、主机却显示没人进来」。现在同时投递给自己的 inbound。
 *  2. **真实 IP**：主机 IP 不再硬编码 192.168.49.1（那是 Wi-Fi Direct GO 的地址），
 *     普通路由器场景下用 localIpv4()，否则对端语音 UDP 发错地址。
 *  3. **加入确认**：收到 Hello 后立即单发 Welcome，客户端收到才算真正互联。
 *  4. **心跳 + 超时剔除**：定期 Ping，超过 9s 无响应的成员移除并重广播，避免僵尸成员。
 */
class ControlServer(
    private val myId: String,
    private val myName: String,
    private val roomName: String = myName,
    private val myAvatar: String = "bear",
    /** 本机 UDP 语音实际端口，随名单下发给客户端。 */
    private val voicePort: Int = 5004,
    private val preferPort: Int = 8888
) {
    private val peers = ConcurrentHashMap<String, PeerConn>()
    @Volatile private var running = false

    /**
     * 对外公布的本机地址。
     * 直连模式下必须填 192.168.49.1（p2p 网卡），否则客户端拿到的语音目标可能是
     * 已经失效的 wlan 地址；普通局域网模式也可以由 RoomManager 显式指定。
     */
    @Volatile var advertisedIp: String? = null

    /** 实际监听端口（可能与约定端口不同），beacon 广播时会带上。 */
    @Volatile var actualPort: Int = preferPort
        private set

    /** 构造期就完成 bind（端口冲突时递增），因此 actualPort 对外立即可用。 */
    private val serverSocket: ServerSocket? = openServerSocket(preferPort) { actualPort = it }

    private val scope = netScope()

    /** 对外事件：UI/业务层订阅。主机自己产生的 PeerList 也会投放到这里（本地环回）。 */
    val inbound = Channel<Msg>(Channel.UNLIMITED)

    /** 当前在线客户端数（不含主机自己）。 */
    val guestCount: Int get() = peers.size

    /** 主机自身 IP：优先局域网，其次 Wi-Fi Direct，最后才用任意网卡兜底。 */
    private fun selfIp(): String =
        advertisedIp ?: lanIpv4() ?: p2pIpv4() ?: localIpv4() ?: "127.0.0.1"

    fun start(): Boolean {
        val s = serverSocket
        if (s == null) {
            Log.e("LocalPTT", "信令监听失败：端口 $preferPort 起均被占用")
            return false
        }
        running = true
        scope.launch {
            while (running) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                launch { handleClient(sock) }
            }
        }
        scope.launch { heartbeatLoop() }
        // 开房后立即给自己一份名单，UI 可立刻显示「房间里只有我」
        broadcastPeerList()
        return true
    }

    private fun handleClient(sock: Socket) {
        // ★ 关闭 Nagle：信令都是几十字节的小包，Nagle + 延迟 ACK 会给每条消息叠加几十到上百毫秒，
        //   游戏里表现为「出招后要等好久才有结果」。
        runCatching { sock.tcpNoDelay = true }
        runCatching { sock.keepAlive = true }
        val writer = runCatching { PrintWriter(sock.getOutputStream(), true) }.getOrNull()
            ?: return
        var id = ""
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            while (running) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                val msg = runCatching { Codec.decode(line) }.getOrNull() ?: continue
                peers[msg.from]?.touch()
                when (msg) {
                    is Hello -> {
                        id = msg.from
                        peers[id] = PeerConn(
                            id = msg.from,
                            name = msg.name,
                            ip = sock.inetAddress?.hostAddress ?: "",
                            avatar = msg.avatar,
                            voicePort = msg.voicePort,
                            out = writer
                        )
                        // ① 明确告知客户端「你已进入房间」，客户端收到才算连上
                        peers[id]?.send(
                            Welcome(myId, id, uid(), myName, roomName, peers[id]?.ip ?: "")
                        )
                        // ② 广播最新名单（并本地环回给自己）
                        broadcastPeerList()
                    }
                    is Pong -> Unit                       // 仅用于刷新 lastSeen
                    is Bye -> {
                        peers.remove(msg.from)
                        broadcastPeerList()
                    }
                    else -> route(msg)                    // 聊天/游戏/视频信令由主机转发
                }
            }
        } catch (e: Exception) {
            Log.w("LocalPTT", "客户端连接异常断开：${e.message}")
        } finally {
            if (id.isNotEmpty()) {
                peers.remove(id)
                broadcastPeerList()
            }
            runCatching { sock.close() }
        }
    }

    /**
     * 转发：定向消息发给指定端；广播消息发给除发送者外的所有人。
     *
     * ★ 关键修复：过去主机自己不在 peers 表里，客户端发「to = 主机 id」的消息
     *   （微聊、游戏出招、视频信令都是定向发送）在 peers[id] 查不到就被丢弃，
     *   表现就是「客户端发消息主机收不到」。现在显式判断目标是不是主机自己。
     */
    private fun route(msg: Msg) {
        val fromMe = msg.from == myId
        // 投递给主机自己的业务流（客户端发来的广播或定向给主机的消息）
        if (!fromMe && (msg.to == "all" || msg.to == myId)) {
            if (msg !is Ping && msg !is Pong) inbound.trySend(msg)
        }
        if (msg.to == "all") {
            peers.forEach { (k, p) -> if (k != msg.from) p.send(msg) }
        } else {
            peers[msg.to]?.send(msg)
        }
    }

    /** 主机作为发送方：把自己的消息按同一路由规则投递给对端。 */
    fun send(msg: Msg) = route(msg)

    /** 主动重发一次名单（改了昵称/头像后让全房间立刻看到新形象）。 */
    fun announce() = broadcastPeerList()

    /**
     * 下发最新名单。
     * ★ 关键修复：名单除了发给所有客户端，还要投递到主机自己的 inbound，
     *   否则主机的 UI 永远停留在「房间开好啦」，看不到有谁加入（两端状态不一致）。
     */
    private fun broadcastPeerList() {
        val list = ArrayList<Peer>(peers.size + 1).apply {
            add(Peer(myId, myName, selfIp(), "host", myAvatar, voicePort))
            peers.values.forEach {
                add(Peer(it.id, it.name, it.ip, "client", it.avatar, it.voicePort))
            }
        }
        val pl = PeerList(myId, "all", uid(), list)
        peers.values.forEach { it.send(pl) }
        inbound.trySend(pl)
    }

    /** 心跳：2.5s 一次 Ping；9s 无响应的成员视为掉线并剔除。 */
    private suspend fun heartbeatLoop() {
        while (running) {
            delay(2500)
            val now = System.currentTimeMillis()
            val dead = peers.values.filter { now - it.lastSeen > 9000 }.map { it.id }
            if (dead.isNotEmpty()) {
                dead.forEach { peers.remove(it) }
                Log.i("LocalPTT", "心跳超时剔除 ${dead.size} 位成员")
                broadcastPeerList()
            }
            peers.values.forEach { it.send(Ping(myId, it.id, uid(), now)) }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        peers.values.forEach { runCatching { it.out.close() } }
        peers.clear()
        scope.cancel()
    }

    private class PeerConn(
        val id: String,
        val name: String,
        val ip: String,
        val avatar: String,
        val voicePort: Int,
        val out: PrintWriter
    ) {
        @Volatile var lastSeen: Long = System.currentTimeMillis()
            private set
        fun touch() { lastSeen = System.currentTimeMillis() }
        fun send(m: Msg) = runCatching { out.println(Codec.encode(m)) }
    }
}

fun uid() = java.util.UUID.randomUUID().toString().take(8)
