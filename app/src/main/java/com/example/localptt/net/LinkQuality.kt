package com.example.localptt.net

/**
 * 链路信号（往返延迟）统计。
 *
 * 为什么需要它：局域网/直连的信号好坏，小朋友只看「连上了」是不够的，
 * 语音卡顿、视频花屏时需要一个能看懂的指示（📶 我 12 ms ｜ 对方 18 ms）。
 *
 * 测量方式：
 *  - 主机：心跳 Ping 的 ts 由客户端 Pong 原样带回，收到 Pong 时算 now - ts；
 *  - 客户端：自己每 3s 发一个 Ping（主机原样回 Pong），同样算 now - ts；
 *  - 各自「对方」的数值由 FeatureSync 状态心跳（每 2s）带过来，
 *    所以两端看到的两个数字来自两条真实测量，不是编的。
 *
 * 数值超过 8 秒没刷新就显示「—」，避免掉线后还挂着一个过期的好信号。
 */
object LinkQuality {

    /** 我自己到房间的往返延迟（ms），-1 = 未知。 */
    @Volatile private var myRtt = -1L
    @Volatile private var myAt = 0L

    /** 对端报告它自己的延迟（ms），-1 = 未知。 */
    @Volatile private var peerRtt = -1L
    @Volatile private var peerAt = 0L

    private const val STALE_MS = 8000L

    /** 记录一次刚测到的往返延迟。 */
    fun noteRtt(ms: Long) {
        myRtt = ms.coerceIn(0L, 9999L)
        myAt = System.currentTimeMillis()
    }

    /** 记录对端在 FeatureSync 里报告的值（0 / 负数表示「它还没测到」）。 */
    fun notePeerReport(ms: Long) {
        if (ms <= 0L) return
        peerRtt = ms.coerceIn(1L, 9999L)
        peerAt = System.currentTimeMillis()
    }

    /** 供 FeatureSync 心跳带走：还没测到时给 0（对端会显示「—」）。 */
    fun reportable(): Long {
        val v = fresh(myRtt, myAt)
        return if (v < 0L) 0L else v
    }

    fun reset() {
        myRtt = -1L; myAt = 0L
        peerRtt = -1L; peerAt = 0L
    }

    /** 各功能页顶部显示的文案，例如「📶 我 12 ms ｜ 对方 18 ms」。 */
    fun summary(): String = "📶 我 ${label(fresh(myRtt, myAt))} ｜ 对方 ${label(fresh(peerRtt, peerAt))}"

    private fun fresh(v: Long, at: Long): Long =
        if (v < 0L || System.currentTimeMillis() - at > STALE_MS) -1L else v

    private fun label(v: Long): String = if (v < 0L) "—" else "$v ms"
}
