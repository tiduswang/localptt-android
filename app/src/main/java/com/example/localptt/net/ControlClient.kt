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
import java.net.Socket

/**
 * 客户端侧 TCP 信令客户端：连接主机(ControlServer)，
 * 发送 Hello、接收 peerlist / welcome、收发 chat/game/video 消息。
 *
 * 修复要点（互联状态不一致的历史 Bug）：
 *  1. **加入需确认**：过去「Socket 连上就 onReady(true)」，主机还没处理 Hello 时客户端已宣称成功，
 *     于是出现「一端显示已连接、另一端显示未连接」。现在必须收到主机 Welcome
 *     （或包含自己的 PeerList）才算互联成功，4s 内没等到即判失败。
 *  2. **断线回调**：读循环结束 / 长时间收不到数据 → onLost()，UI 才能及时显示掉线并重连。
 *  3. **心跳应答**：收到主机 Ping 立即回 Pong，否则会被主机 9s 超时剔除。
 */
class ControlClient(
    val hostIp: String,
    private val myId: String,
    private var myName: String,
    private var myAvatar: String = "bear",
    private val port: Int = 8888,
    /** 本机 UDP 语音实际端口，随 Hello 告诉主机。 */
    private val voicePort: Int = 5004
) {
    private var sock: Socket? = null
    private var out: PrintWriter? = null
    private val scope = netScope()

    /** 收到的所有信令消息（含 peerlist / chat / game / video）。 */
    val inbound = Channel<Msg>(Channel.UNLIMITED)

    @Volatile var connected = false
        private set

    /** 最近一次收到数据的时间，用于掉线检测。 */
    @Volatile private var lastRecv = 0L

    /** 保证 onReady / onLost 各只回调一次，避免 UI 状态被反复覆盖。 */
    @Volatile private var readyFired = false
    @Volatile private var lostFired = false

    companion object {
        private const val CONFIRM_TIMEOUT_MS = 4000L   // 等待主机确认
        private const val SILENCE_TIMEOUT_MS = 10000L  // 静默多久算掉线
        private const val CHECK_INTERVAL_MS = 2000L

        /**
         * TCP 建连超时。
         *
         * ★ 历史 Bug：过去写的是 `Socket(hostIp, port)`，没有连接超时。目标不可达
         *   （例如房间已经散了、但本地还记着旧 IP）时，这一步会阻塞几十秒到几分钟，
         *   期间上层一直停留在「正在处理中」——用户看到的就是 App 卡死。
         *   有超时后，最坏情况 3s 返回失败，UI 立刻可再次操作。
         */
        /**
         * 建连重试次数与间隔。
         *
         * 直连（Wi-Fi Direct）刚成组时，对端的 p2p 网卡/监听可能还差几百毫秒才就绪，
         * 一次失败就宣告「进不去」太武断；换新 socket 重试几次能把这类瞬态失败吃掉。
         */
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val CONNECT_RETRIES = 3
        private const val CONNECT_RETRY_DELAY = 900L
    }

    /**
     * @param onReady   互联确认结果（true=主机已确认你在房间里）
     * @param onLost    已连接后又断开（主机退出 / Wi-Fi 中断）
     * @param onPhase   连接阶段变化（"connecting" / "connected"），供 UI 显示「卡在哪一步」
     */
    fun connect(
        onReady: (Boolean) -> Unit,
        onLost: () -> Unit = {},
        onPhase: (String) -> Unit = {}
    ) {
        readyFired = false
        lostFired = false
        runCatching { onPhase("connecting") }
        scope.launch {
            var confirmed = false
            var established: Socket? = null
            try {
                var candidateOk: Socket? = null
                var lastError: Throwable? = null
                for (attempt in 1..CONNECT_RETRIES) {
                    val candidate = Socket()
                    val r = runCatching {
                        candidate.connect(
                            java.net.InetSocketAddress(hostIp, port), CONNECT_TIMEOUT_MS
                        )
                        candidate.tcpNoDelay = true       // 信令是小包，禁用 Nagle 降低延迟
                        candidate.keepAlive = true
                    }
                    if (r.isSuccess) { candidateOk = candidate; break }
                    lastError = r.exceptionOrNull()
                    runCatching { candidate.close() }
                    Log.w("LocalPTT", "第 $attempt 次连 $hostIp:$port 失败：${lastError?.message}")
                    if (attempt < CONNECT_RETRIES) delay(CONNECT_RETRY_DELAY)
                }
                val s = candidateOk
                    ?: throw (lastError ?: IllegalStateException("连接主机失败"))
                established = s
                sock = s
                out = PrintWriter(s.getOutputStream(), true)
                lastRecv = System.currentTimeMillis()
                runCatching { onPhase("connected") }
                send(Hello(myId, "all", uid(), myName, "client", myAvatar, voicePort))

                // ① 超时未获主机确认 → 判失败
                launch {
                    delay(CONFIRM_TIMEOUT_MS)
                    if (!confirmed && !readyFired) {
                        readyFired = true
                        Log.w("LocalPTT", "等待主机确认超时 $hostIp:$port")
                        runCatching { s.close() }
                        onReady(false)
                    }
                }

                // ② 静默超时 → 判掉线
                launch {
                    while (true) {
                        delay(CHECK_INTERVAL_MS)
                        if (System.currentTimeMillis() - lastRecv > SILENCE_TIMEOUT_MS) {
                            Log.w("LocalPTT", "与主机 $hostIp 失去联系（静默超时）")
                            connected = false
                            fireLost(onLost)
                            runCatching { s.close() }
                            break
                        }
                    }
                }

                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                while (true) {
                    val line = runCatching { reader.readLine() }.getOrNull() ?: break
                    val msg = runCatching { Codec.decode(line) }.getOrNull() ?: continue
                    lastRecv = System.currentTimeMillis()

                    if (msg is Ping) {                     // 心跳：立即应答，不入业务流
                        send(Pong(myId, msg.from, uid(), msg.ts))
                        continue
                    }

                    if (!confirmed &&
                        (msg is Welcome || (msg is PeerList && msg.peers.any { it.id == myId }))
                    ) {
                        confirmed = true
                        connected = true
                        if (!readyFired) {
                            readyFired = true
                            onReady(true)
                        }
                    }
                    inbound.trySend(msg)
                }

                // 读循环结束 = 主机关闭连接或网络中断
                connected = false
                if (confirmed) fireLost(onLost)
                else if (!readyFired) { readyFired = true; onReady(false) }
            } catch (e: Exception) {
                Log.w("LocalPTT", "连接主机 $hostIp:$port 失败：${e.message}")
                connected = false
                if (!readyFired) { readyFired = true; onReady(false) }
                else fireLost(onLost)
            } finally {
                connected = false
                runCatching { established?.close() }
            }
        }
    }

    private fun fireLost(onLost: () -> Unit) {
        if (lostFired) return
        lostFired = true
        runCatching { onLost() }
    }

    fun send(m: Msg) = runCatching { out?.println(Codec.encode(m)) }

    /** 改名/换头像后重新发一次 Hello，主机据此更新名单里的形象（不用重新连接）。 */
    fun updateProfile(name: String, avatar: String) {
        myName = name
        myAvatar = avatar
        send(Hello(myId, "all", uid(), name, "client", avatar, voicePort))
    }

    fun disconnect() {
        runCatching { send(Bye(myId, "all", uid())) }
        runCatching { sock?.close() }
        sock = null
        out = null
        connected = false
        scope.cancel()
    }
}
