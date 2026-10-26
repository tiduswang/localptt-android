package com.example.localptt.net

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** 视频包类型标记（UDP 负载首字节）。 */
object VideoMarker {
    const val NON_KEY = 0     // 非关键帧（P/B）
    const val IDR = 1         // 关键帧（IDR）
    const val CONFIG = 2      // 编码器参数集（csd-0/csd-1，即 SPS/PPS）
}

/**
 * 极简 RTP over UDP（仅本地局域网用，固定序号/无 RTCP 反馈）。
 * 负载直接承载 H.264 裸 NALU（一个 NAL 一包）。
 * 首字节为 [VideoMarker] 标记，便于对端识别关键帧与参数集。
 *
 * ★ 端口自适应（修复「双方都显示通话中，但谁也看不到画面」）：
 *   本机接收端口可能因被占用而自动递增到别的端口（见 NetGuard.openUdpSocket），
 *   而发送端仍按约定的 5006 发送 → 收发端口对不上，画面永远是黑的。
 *   现在收发同端口，并且**从收到的包里学习对端的真实源端口**，后续包发给它，
 *   于是即便两端各自实际绑定的端口不同，也能在收到第一个包后自动对齐。
 *
 * 已知限制：未做 RTP 分片（FU-A），单帧 > ~64KB 时会因 UDP 分片在弱网下易丢包；
 * 生产环境建议改用 WebRTC 媒体栈或自行实现 FU-A 分片。
 */
class RtpSocket(private val peerIp: String, private val port: Int) {
    private val sock = DatagramSocket()          // 发送用，自动分配本地端口

    /** 接收用 socket（占用时由 openUdpSocket 自动递增端口）。 */
    private val rx: DatagramSocket? = openUdpSocket(port) { Log.i("LocalPTT", "视频 RTP 接收端口 $it") }

    private val addr = runCatching { InetAddress.getByName(peerIp) }.getOrNull()
    private val scope = netScope()

    /** 从收到的包里学到的对端真实源端口（0 表示还没学到）。 */
    @Volatile private var learnedPort: Int = 0

    /** 发送目标端口：优先用学到的真实端口，学到之前按约定端口。 */
    private val targetPort: Int get() { val p = learnedPort; return if (p > 0) p else port }

    fun open() { /* 保持接口兼容；实际收发在 send/receive 中惰性进行 */ }

    /** 发送一个视频包：[marker](1B) + payload。 */
    fun send(marker: Int, payload: ByteArray) {
        val a = addr ?: return
        val pkt = ByteArray(payload.size + 1)
        pkt[0] = (marker and 0xFF).toByte()
        payload.copyInto(pkt, 1)
        runCatching { sock.send(DatagramPacket(pkt, pkt.size, a, targetPort)) }
    }

    /** 循环接收，回调 (payload, marker)。 */
    fun receive(onPacket: (ByteArray, Int) -> Unit) {
        val s = rx
        if (s == null) { Log.w("LocalPTT", "视频 RTP 接收端口不可用，收不到画面"); return }
        scope.launch {
            val buf = ByteArray(65535)
            while (isActive) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { s.receive(p) }.onFailure { return@launch }
                val n = p.length
                if (n <= 1) continue
                // 记住对端实际发包端口：这是两端端口不一致时唯一可靠的对齐方式
                val src = p.port
                if (src > 0 && src != learnedPort) {
                    learnedPort = src
                    Log.i("LocalPTT", "学到对端视频源端口 $src")
                }
                val marker = buf[0].toInt() and 0xFF
                onPacket(buf.copyOfRange(1, n), marker)
            }
        }
    }

    fun close() { runCatching { sock.close() }; runCatching { rx?.close() }; scope.cancel() }
}
