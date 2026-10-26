package com.example.localptt.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.localptt.net.UdpBus
import com.example.localptt.net.netGuard
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 实时语音对讲引擎。两种模式：
 *  - **PTT 半双工**（[pushToTalk]）：按住说话、松开停，语音对讲页用；
 *  - **免提全双工**（[startHandsFree]）：持续采集 + 持续播放，视频通话页用。
 *
 *   采集(AudioRecord) -> 编码(AudioCodec) -> UDP 广播(UdpBus)
 *   接收(UdpBus) -> 解码(AudioCodec) -> 播放(AudioTrack)，带简单抖动缓冲。
 *
 * ★ 音量/扬声器修复（历史问题「声音太轻、只能从听筒出声」）：
 *  1. **强制扬声器**：AudioManager 切 MODE_IN_COMMUNICATION 并打开 isSpeakerphoneOn；
 *     AudioTrack 用 USAGE_VOICE_COMMUNICATION（低延迟通话通路）+ CONTENT_TYPE_SPEECH，
 *     外放时音量走通话流，比媒体流稳定且不会被系统限流。
 *  2. **软件增益**：解码后对 PCM 放大 [playGain] 倍（默认 3 倍，带削幅保护）；
 *     采集端先过硬件 AGC（[AutomaticGainControl]）再乘 [recGain]（默认 2 倍），
 *     解决「对端听到的声音很小」。
 *  3. **扬声器开关**：[setSpeaker] 会重建 AudioTrack 并切换音频路由。
 *
 * 健壮性（历史闪退主因）：
 *  - 编解码器与 AudioRecord/AudioTrack 的创建全部空安全 + 参数降级，
 *    设备不支持 AMR-NB 或 VOICE_COMMUNICATION 音源时不再抛异常崩溃，
 *    而是自动降级（AMR 不可用 -> PCM 直通；通话音源不可用 -> 麦克风）。
 */
class AudioEngine(
    private val ctx: Context,
    private val bus: UdpBus,
    private val useOpus: Boolean = false
) {

    private val codec: AudioCodec = createCodec()
    private val sampleRate = codec.sampleRate
    private val frameSamples = codec.frameSamples        // 20ms

    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + netGuard)

    @Volatile private var talking = false
    @Volatile private var handsFree = false
    private var seq = 0
    private var captureJob: Job? = null

    // 播放侧抖动缓冲：按序号排序后稳定出帧
    private val jitter = ConcurrentLinkedQueue<UdpBus.AudioFrame>()

    /** 播放增益（解码后放大）。调大可让对方/自己听到的声音更响。 */
    @Volatile var playGain = 1.6f
    /** 采集增益（编码前放大）。PCM 直通会保留原始电平，采集端不再叠加太多增益（配合硬件 AGC）。 */
    @Volatile var recGain = 1.0f
    /** 是否走扬声器（false = 听筒）。 */
    @Volatile var speakerphone = true

    /** 是否静音（不听对端）。静音时收到的语音帧直接丢弃。 */
    @Volatile var playbackMuted = false
        private set

    /** 本机有没有可用的麦克风 / 扬声器（UI 用来提示，而不是静默失败）。 */
    val canRecord: Boolean get() = rec != null
    val canPlay: Boolean get() = track != null

    private val am: AudioManager? by lazy {
        runCatching { ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    }
    private var savedMode: Int? = null
    private var savedSpeaker: Boolean? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var focusRequest: AudioFocusRequest? = null

    private fun createCodec(): AudioCodec {
        if (useOpus) {
            runCatching { OpusCodec() }.onSuccess { return it }
                .onFailure { Log.w("LocalPTT", "Opus 不可用：${it.message}") }
        }
        // ★ 关键修复（「语音对讲听不到对方」）：以前默认用 AMR-NB（MediaCodec 软件编解码），
        //   两端设备的 AMR 支持情况不一定一致，且逐帧 dequeue 只有 10ms 超时，常常拿到空帧：
        //   编码端静音、解码端解出空 PCM，表现就是完全听不到。
        //   局域网/直连带宽完全够用，这里统一走 PCM 直通（8kHz 单声道约 128kbps），
        //   任何机型行为一致，彻底消除编解码器差异。
        Log.i("LocalPTT", "语音编码：PCM 直通（8kHz 单声道）")
        return RawCodec()
    }

    /** @return 采集或播放是否至少有一路可用。 */
    fun start(): Boolean {
        return try {
            rec = buildRecorder()
            track = buildTrack()
            if (rec == null && track == null) {
                Log.w("LocalPTT", "录音与放音设备均不可用")
                return false
            }
            applyRouting()
            // 参考 meshenger：通话类音频必须抢 AudioFocus，否则被音乐/其它 App 抢走时直接没声
            requestFocus()
            // ★ 麦克风常开：PTT 只在「发不发包」上做开关，不再反复 startRecording/stopRecording。
            //   过去每次按下都要重新启动录音（几十到几百毫秒），导致「开头一两个字被吞掉」。
            rec?.let { r ->
                attachEffects(r.audioSessionId)
                runCatching { r.startRecording() }
                captureJob?.cancel()
                captureJob = scope.launch { captureLoop() }
            }
            bus.startReceiving()
            scope.launch { receiveLoop() }
            true
        } catch (e: Throwable) {
            Log.w("LocalPTT", "音频引擎启动失败：${e.message}")
            false
        }
    }

    /** 优先通话音源（自带 AEC/NS），设备不支持时退回普通麦克风。 */
    private fun buildRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buf = (if (minBuf <= 0) frameSamples * 4 else minBuf) * 2
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        // 先 MIC：VOICE_COMMUNICATION 在部分机型上会做强回声消除，
        // 把 PTT 这种「近距离大声说话」的人声也压掉，表现为「声音很轻」。
        for (src in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)) {
            runCatching {
                AudioRecord.Builder().setAudioSource(src).setAudioFormat(fmt)
                    .setBufferSizeInBytes(buf).build()
            }.onSuccess {
                if (it.state == AudioRecord.STATE_INITIALIZED) return it
                runCatching { it.release() }
            }.onFailure { Log.w("LocalPTT", "AudioRecord 源 $src 创建失败：${it.message}") }
        }
        return null
    }

    private fun buildTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buf = (if (minBuf <= 0) frameSamples * 4 else minBuf) * 2
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        // ★ 固定使用「通话音轨」：与 MODE_IN_COMMUNICATION 匹配，音量走通话流，
        //   过去扬声器模式用 USAGE_MEDIA、听筒模式用 USAGE_VOICE_COMMUNICATION，
        //   两者与 AudioManager.mode 混搭时，部分机型会直接把播放静音。
        runCatching {
            val b = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
            // 低延迟播放通路（参考 meshenger 的低延迟通话）：API 26+ 可用
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            b.build()
        }.onSuccess {
            if (it.state == AudioTrack.STATE_INITIALIZED) return it
            runCatching { it.release() }
        }.onFailure { Log.w("LocalPTT", "AudioTrack 创建失败：${it.message}") }
        return null
    }

    /** 切到通话音量路由 + 扬声器/听筒：这是「能听到对方」的前提。 */
    private fun applyRouting() {
        val m = am ?: return
        runCatching {
            if (savedMode == null) { savedMode = m.mode; savedSpeaker = m.isSpeakerphoneOn }
            // 无论外放还是听筒都保持通话模式：AudioTrack 用的是通话音轨，只有在通话模式下才会真正出声
            m.mode = AudioManager.MODE_IN_COMMUNICATION
            m.isSpeakerphoneOn = speakerphone
            // 通话流音量过低时抬到 70%，避免「开到最大还是很轻」
            runCatching { raiseStreamIfTooLow(AudioManager.STREAM_VOICE_CALL) }
        }.onFailure { Log.w("LocalPTT", "音频路由设置失败：${it.message}") }
    }

    private fun raiseStreamIfTooLow(stream: Int) {
        val m = am ?: return
        val max = m.getStreamMaxVolume(stream)
        if (max <= 0) return
        if (m.getStreamVolume(stream) < max * 0.7f) {
            m.setStreamVolume(stream, (max * 0.7f).toInt().coerceAtLeast(1), 0)
        }
    }

    /**
     * 音频效果链（参考 meshenger 的 JavaAudioDeviceModule 配置）：
     * AGC 提音量、AEC 消对讲外放的回声、NS 压环境噪声。设备支持才挂，避免抛异常。
     */
    private fun attachEffects(sessionId: Int) {
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        }.onFailure { Log.w("LocalPTT", "AGC 不可用：${it.message}") }
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        }.onFailure { Log.w("LocalPTT", "AEC 不可用：${it.message}") }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        }.onFailure { Log.w("LocalPTT", "NS 不可用：${it.message}") }
    }

    /** 抢音频焦点（通话类），release 时归还。 */
    private fun requestFocus() {
        val m = am ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    ).build()
                focusRequest = req
                m.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                m.requestAudioFocus(
                    null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }.onFailure { Log.w("LocalPTT", "音频焦点申请失败：${it.message}") }
    }

    private fun abandonFocus() {
        val m = am ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { m.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                m.abandonAudioFocus(null)
            }
        }
        focusRequest = null
    }

    /** 切换扬声器/听筒（只改音频路由，不重建音轨，避免切换瞬间丢声音）。 */
    fun setSpeaker(on: Boolean) {
        speakerphone = on
        applyRouting()
    }

    /** 静音/取消静音（不听对端）。 */
    fun setPlaybackMuted(muted: Boolean) {
        playbackMuted = muted
        if (muted) {
            runCatching { track?.pause() }
            jitter.clear()
        } else {
            runCatching { track?.play() }
        }
    }

    /**
     * 按住说话：只切换「发不发包」，麦克风保持常开（按键即出声，没有启动延迟）。
     * 参考 meshenger 的 PTT：用轨道 enable/disable 而不是反复停启采集。
     */
    fun pushToTalk(on: Boolean) {
        if (on) {
            if (rec == null) return
            talking = true
            runCatching { track?.play() }
        } else {
            talking = false
        }
    }

    /** 免提常开（视频通话用）：一直采集 + 一直播放。 */
    fun startHandsFree() {
        if (handsFree) return
        handsFree = true
        runCatching { track?.play() }
    }

    fun stopHandsFree() {
        handsFree = false
    }

    private suspend fun CoroutineScope.captureLoop() {
        val pcm = ShortArray(frameSamples)
        while (isActive) {
            val n = runCatching { rec?.read(pcm, 0, frameSamples) ?: 0 }.getOrDefault(0)
            if (n <= 0) { delay(20); continue }     // 读失败/无数据：别空转烧 CPU
            // 没在说话就把缓冲读空，不编码、不发包
            if (!talking && !handsFree) continue
            applyGain(pcm, n, recGain)
            val enc = runCatching { codec.encode(pcm, n) }.getOrDefault(ByteArray(0))
            if (enc.isNotEmpty()) bus.sendFrame(seq++, enc)
        }
    }

    private suspend fun receiveLoop() {
        runCatching { track?.play() }
        for (f in bus.inbound) {
            // 静音时不写入音轨，也不让抖动队列越积越多
            if (playbackMuted) { jitter.clear(); continue }
            jitter.add(f)
            // 缓冲 2 帧后再播，抗抖动；溢出则丢弃最旧帧，避免延迟累积
            while (jitter.size > 8) jitter.poll()
            if (jitter.size < 2) continue
            val frame = jitter.poll() ?: continue
            val pcm = runCatching { codec.decode(frame.payload, frame.payload.size) }
                .getOrDefault(ShortArray(0))
            if (pcm.isEmpty()) continue
            applyGain(pcm, pcm.size, playGain)
            runCatching { track?.write(pcm, 0, pcm.size) }
        }
    }

    /** 数字增益：放大并做削幅保护，避免溢出变成爆音。 */
    private fun applyGain(pcm: ShortArray, len: Int, gain: Float) {
        if (gain <= 1.0001f) return
        val n = len.coerceIn(0, pcm.size)
        for (i in 0 until n) {
            val v = (pcm[i] * gain)
            pcm[i] = when {
                v > 32767f -> 32767
                v < -32768f -> -32768
                else -> v
            }.toInt().toShort()
        }
    }

    fun release() {
        talking = false
        handsFree = false
        captureJob?.cancel()
        captureJob = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { agc?.release() }
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { codec.release() }
        abandonFocus()
        // 还原系统音频路由，别把其它 App 的通话模式带歪
        runCatching {
            val m = am ?: return@runCatching
            savedMode?.let { m.mode = it }
            savedSpeaker?.let { m.isSpeakerphoneOn = it }
        }
        scope.cancel()
        rec = null; track = null; agc = null; aec = null; ns = null
    }
}

/**
 * AMR-NB 编解码（MediaCodec 软件实现，码率 4.75~12.2kbps）。
 * 用于尚未集成 libopus 的阶段；两者接口一致，可随时切换。
 *
 * 帧格式遵循 RFC 3267 / 3GPP TS 26.101：每帧 = 1 字节 ToC 头 + 语音负载，
 * ToC 头 bit7=F(末帧标志)，bit6..3=FT(帧类型0-7)，bit2=Q(质量)，
 * 因此 FT = (ToC >> 3) and 0x0F，帧总长(含 ToC) = FRAME_SIZES[FT]。
 *
 * 关键点：MediaCodec 改为惰性创建，部分机型缺少 AMR 编解码器时不再构造失败崩溃，
 * 而是 [available] 返回 false，由 AudioEngine 降级到 PCM 直通。
 */
class AmrCodec : AudioCodec {
    override val sampleRate = 8000                 // AMR-NB 固定 8kHz
    override val frameSamples = 160                // 20ms

    private val enc: MediaCodec? by lazy { runCatching { createEnc() }.getOrNull() }
    private val dec: MediaCodec? by lazy { runCatching { createDec() }.getOrNull() }

    private var ptsUs = 0L

    fun available(): Boolean = enc != null && dec != null

    private fun createEnc(): MediaCodec =
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB).apply {
            configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, sampleRate, 1)
                    .apply { setInteger(MediaFormat.KEY_BIT_RATE, 7950) },   // MR795
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            start()
        }

    private fun createDec(): MediaCodec =
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB).apply {
            configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, sampleRate, 1),
                null, null, 0
            )
            start()
        }

    override fun encode(pcm: ShortArray, pcmLen: Int): ByteArray {
        val e = enc ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        try {
            // 1) 喂入一帧 PCM（16bit LE）
            val inIdx = e.dequeueInputBuffer(TIMEOUT_US)
            if (inIdx >= 0) {
                val ib = e.getInputBuffer(inIdx) ?: return out.toByteArray()
                ib.clear()
                val bytes = toLeBytes(pcm, pcmLen)
                ib.put(bytes)
                e.queueInputBuffer(inIdx, 0, bytes.size, ptsUs, 0)
                ptsUs += 20_000
            }
            // 2) 取出一帧（或多帧）AMR，过滤掉 CODEC_CONFIG
            val info = MediaCodec.BufferInfo()
            var outIdx = e.dequeueOutputBuffer(info, TIMEOUT_US)
            while (outIdx >= 0) {
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val ob = e.getOutputBuffer(outIdx) ?: break
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val b = ByteArray(info.size); ob.get(b)
                    out.write(b)
                }
                e.releaseOutputBuffer(outIdx, false)
                outIdx = e.dequeueOutputBuffer(info, 0)
            }
        } catch (t: Throwable) {
            Log.w("LocalPTT", "AMR 编码异常：${t.message}")
        }
        return out.toByteArray()
    }

    override fun decode(data: ByteArray, len: Int): ShortArray {
        val d = dec ?: return ShortArray(0)
        val pcm = ByteArrayOutputStream()
        var off = 0
        try {
            // 按 ToC 头逐帧解析；一旦同步丢失就丢弃剩余，避免错误扩散
            while (off + 1 <= len) {
                val ft = (data[off].toInt() shr 3) and 0x0F
                if (ft < 0 || ft > 7) break
                val fsz = FRAME_SIZES[ft]
                if (off + fsz > len) break

                val inIdx = d.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val ib = d.getInputBuffer(inIdx)
                    ib?.clear()
                    ib?.put(data, off, fsz)
                    d.queueInputBuffer(inIdx, 0, fsz, 0, 0)
                }
                off += fsz

                val info = MediaCodec.BufferInfo()
                var outIdx = d.dequeueOutputBuffer(info, TIMEOUT_US)
                while (outIdx >= 0) {
                    if (info.size > 0) {
                        val ob = d.getOutputBuffer(outIdx)
                        if (ob != null) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            val b = ByteArray(info.size); ob.get(b)
                            pcm.write(b)
                        }
                    }
                    d.releaseOutputBuffer(outIdx, false)
                    outIdx = d.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (t: Throwable) {
            Log.w("LocalPTT", "AMR 解码异常：${t.message}")
        }
        return fromLeBytes(pcm.toByteArray())
    }

    override fun release() {
        runCatching { enc?.stop(); enc?.release() }
        runCatching { dec?.stop(); dec?.release() }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L        // 10ms，小于 20ms 帧预算

        /** AMR-NB 帧总长（含 1 字节 ToC），索引 = FT(0..7)。 */
        private val FRAME_SIZES = intArrayOf(13, 14, 16, 18, 20, 21, 27, 32)

        /** ShortArray(16bit) -> 小端字节序。 */
        fun toLeBytes(pcm: ShortArray, len: Int): ByteArray {
            val safe = len.coerceIn(0, pcm.size)
            val b = ByteArray(safe * 2)
            for (i in 0 until safe) {
                val v = pcm[i].toInt()
                b[i * 2] = (v and 0xFF).toByte()
                b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return b
        }

        /** 小端字节序 -> ShortArray(16bit)。 */
        fun fromLeBytes(b: ByteArray): ShortArray {
            val n = b.size / 2
            val out = ShortArray(n)
            for (i in 0 until n) {
                val lo = b[i * 2].toInt() and 0xFF
                val hi = b[i * 2 + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort()
            }
            return out
        }
    }
}

/**
 * 兜底编解码器：直接透传 16bit PCM（8kHz/20ms = 320B 帧）。
 * AMR/Opus 均不可用时使用，保证「能通话」优先于「省带宽」。
 */
class RawCodec : AudioCodec {
    override val sampleRate = 8000
    override val frameSamples = 160
    override fun encode(pcm: ShortArray, pcmLen: Int): ByteArray = AmrCodec.toLeBytes(pcm, pcmLen)
    override fun decode(data: ByteArray, len: Int): ShortArray =
        AmrCodec.fromLeBytes(if (len == data.size) data else data.copyOf(len))
    override fun release() {}
}
