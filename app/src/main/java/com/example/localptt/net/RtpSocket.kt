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
 * ★ 关键修复（「视频只能看到自己、看不到对端」的根因）：
 *   过去**发送**用 `DatagramSocket()`（系统随机分配一个临时端口），**接收**用另一个绑定 5006 的 socket。
 *   双方收到对端第一包后，把「对端发包的临时端口」记成目标端口，
 *   之后的视频流全部发到那个**根本没人监听**的临时端口 → 双方都收不到后续帧，只剩本地预览。
 *   现在收发共用同一个绑定端口（源端口 == 监听端口），对端学到的端口就是真正可达的端口；
 *   即使本机端口被占用顺延，也能靠首包学习自动对齐。
 */
class RtpSocket(private val peerIp: String, private val port: Int) {

    /** 实际绑定到的本机端口（5006 被占用时会顺延）。 */
    @Volatile var actualPort: Int = port
        private set

    /** 收发共用的唯一 socket。 */
    private val sock: DatagramSocket? = openUdpSocket(port) {
        actualPort = it
        Log.i("LocalPTT", "视频 RTP 端口 $it（收发共用）")
    }

    private val addr = runCatching { InetAddress.getByName(peerIp) }.getOrNull()
    private val scope = netScope()

    /** 从收到的包里学到的对端真实端口（0 表示还没学到）。 */
    @Volatile private var learnedPort: Int = 0

    /** 发送目标端口：优先用学到的真实端口，学到之前按约定端口。 */
    private val targetPort: Int get() { val p = learnedPort; return if (p > 0) p else port }

    @Volatile private var receiving = false

    /** 发送一个视频包：[marker](1B) + payload。 */
    fun send(marker: Int, payload: ByteArray) {
        val s = sock ?: return
        val a = addr ?: return
        val pkt = ByteArray(payload.size + 1)
        pkt[0] = (marker and 0xFF).toByte()
        payload.copyInto(pkt, 1)
        runCatching { s.send(DatagramPacket(pkt, pkt.size, a, targetPort)) }
    }

    /** 循环接收，回调 (marker, payload)。只允许启动一次，避免多个循环抢同一个 socket。 */
    fun receive(onPacket: (Int, ByteArray) -> Unit) {
        val s = sock
        if (s == null) {
            Log.w("LocalPTT", "视频 RTP 端口不可用，收不到画面")
            return
        }
        if (receiving) return
        receiving = true
        scope.launch {
            val buf = ByteArray(65535)
            while (isActive) {
                val p = DatagramPacket(buf, buf.size)
                val ok = runCatching { s.receive(p) }.isSuccess
                if (!ok) { receiving = false; return@launch }
                val n = p.length
                if (n <= 1) continue
                // 记住对端实际发包端口：两端端口不一致时靠它自动对齐
                val src = p.port
                if (src > 0 && src != learnedPort) {
                    learnedPort = src
                    Log.i("LocalPTT", "学到对端视频端口 $src")
                }
                val marker = buf[0].toInt() and 0xFF
                runCatching { onPacket(marker, buf.copyOfRange(1, n)) }
                    .onFailure { Log.w("LocalPTT", "处理视频包异常：${it.message}") }
            }
        }
    }

    fun close() {
        receiving = false
        runCatching { sock?.close() }
        scope.cancel()
    }
}
