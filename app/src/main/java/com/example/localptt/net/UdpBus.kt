package com.example.localptt.net

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 语音媒体总线：基于 UDP 的「按下即说(PTT)」广播通道。
 * 任一节点说话时把编码后的音频帧发给组内所有对端；接收线程把帧投到 [inbound]。
 *
 * 健壮性要点（历史闪退主因）：
 *  - 接收端口固定 5004，但若被占用（通常是旧的 UdpBus 没释放）则递增，绝不抛异常崩溃；
 *  - 发送目标端口始终是约定端口 5004，与实际绑定端口解耦，保证互通；
 *  - socket 允许为 null，收发全部空安全处理。
 */
class UdpBus(
    private val myId: String,
    private val port: Int = 5004
) {
    /** 实际绑定的接收端口（可能与约定端口不同）。 */
    @Volatile private var rxPort: Int = port

    private val socket: DatagramSocket? = openUdpSocket(port) { rxPort = it }
    private val scope = netScope()

    /** 收到的音频帧（来自某节点 + 序号 + 负载）。 */
    data class AudioFrame(val fromId: String, val seq: Int, val payload: ByteArray)

    val inbound = kotlinx.coroutines.channels.Channel<AudioFrame>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )

    private val targets = mutableSetOf<InetAddress>()

    fun addTarget(ip: String) {
        runCatching { InetAddress.getByName(ip) }
            .onSuccess { synchronized(targets) { targets.add(it) } }
    }

    fun startReceiving() = scope.launch {
        val s = socket ?: return@launch
        val buf = ByteArray(1024)
        while (true) {
            val pkt = DatagramPacket(buf, buf.size)
            runCatching { s.receive(pkt) }.onFailure { return@launch }
            val n = pkt.length
            if (n <= 10) continue
            runCatching {
                // 帧头：fromId(8B) + seq(4B) + payload
                val from = String(pkt.data, 0, 8).trim()
                val seq = (pkt.data[8].toInt() and 0xFF) or ((pkt.data[9].toInt() and 0xFF) shl 8)
                val payload = pkt.data.copyOfRange(10, n)
                inbound.trySend(AudioFrame(from, seq, payload))
            }
        }
    }

    fun sendFrame(seq: Int, payload: ByteArray) {
        val s = socket ?: return
        val header = ByteArray(10).apply {
            myId.padEnd(8, ' ').toByteArray().copyInto(this, 0, 0, 8)
            this[8] = (seq and 0xFF).toByte()
            this[9] = (seq shr 8 and 0xFF).toByte()
        }
        val frame = header + payload
        val snapshot = synchronized(targets) { targets.toList() }
        for (t in snapshot) {
            runCatching { s.send(DatagramPacket(frame, frame.size, t, port)) }
        }
    }

    fun release() {
        runCatching { socket?.close() }
        scope.cancel()
    }
}
