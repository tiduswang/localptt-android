package com.example.localptt.video

import com.example.localptt.net.netScope
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import com.example.localptt.net.RtpSocket
import com.example.localptt.net.VideoMarker
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * 实时视频对讲（1 对 1 点对点）。
 *
 *   本地：Camera2 -> encoder.createInputSurface() -> MediaCodec H.264 -> RTP(UDP) -> 对端
 *   对端：RTP -> MediaCodec H.264 解码 -> Surface(SurfaceView) 渲染
 *
 * 关键点：
 *  - 编码器 configure 时 surface 传 null，改用 [MediaCodec.createInputSurface] 作为 Camera2 的输出目标，
 *    这样 Camera2 直接把帧写入编码器，无需在 onInputBufferAvailable 里手动拷贝，零拷贝、低延迟。
 *  - 参数集（csd-0/csd-1 = SPS/PPS）在 onOutputFormatChanged 中取出并单独发给对端；
 *    对端以 BUFFER_FLAG_CODEC_CONFIG 喂给解码器，之后再喂关键帧/非关键帧。
 *  - 本地无 NAT，无需 STUN/TURN，直接以对端 LAN IP 收发。
 *
 * ★ 机型兼容性修复（华为 P40 / Android 10 等「能打通但看不到画面」）：
 *  1. **CBR 替代 VBR**：部分麒麟/华为编码器在 VBR 下不出帧或码率失控。
 *  2. **显式 Baseline profile + level**：编码器默认可能产出 High profile，
 *     老机型解码器解不动，画面永远是黑的。
 *  3. **分辨率/帧率逐级降级**：640x480@20 不支持时依次退到 @15、320x240@15。
 *  4. **相机会话降级**：本地预览 + 编码器双 Surface 不被支持时，退回只输出给编码器。
 *  5. **帧率区间按摄像头能力挑选**：写死 Range(fps,fps) 会让部分机型直接拒绝预览请求。
 *  6. **周期性请求关键帧**：对端丢帧/参数集变化后能迅速恢复画面。
 */
class VideoCallSession(
    private val ctx: Context,
    private val peerIp: String,
    private val previewSurface: Surface,     // 本地预览（TextureView 的 Surface）
    private val remoteSurface: Surface,      // 远端渲染（SurfaceView 的 Surface）
    private val port: Int = 5006,
    /** 关键错误回调（相机打不开 / 编码器不可用等），用于 UI 提示而不是静默失败。 */
    private val onError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "VideoCall"

        /** 兼容性候选（宽、高、帧率），从高到低逐个尝试，直到 configure 成功。 */
        private val CANDIDATES = listOf(
            intArrayOf(640, 480, 20),
            intArrayOf(640, 480, 15),
            intArrayOf(320, 240, 15)
        )

        /**
         * 挑出对讲要用的相机（优先前置）并读出它的 **sensor 方向**。
         *
         * Activity 在建会话之前就要用它把本地画中画摆正：Camera2 输出的是 sensor 原始画幅
         * （多数机型是横的），直接塞进竖的小窗里，人就横着了。
         */
        fun pickCamera(ctx: Context): CamInfo? {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            val ids = runCatching { cm.cameraIdList }.getOrDefault(emptyArray())
            var fallback: CamInfo? = null
            for (id in ids) {
                val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
                val front = ch.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
                val info = CamInfo(
                    id = id,
                    sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    front = front
                )
                if (front) return info       // 对讲优先前置：孩子看的是屏幕这边
                if (fallback == null) fallback = info
            }
            return fallback
        }
    }

    /** 相机标识 + 画面方向（sensor 角度）+ 是否前置。 */
    data class CamInfo(val id: String, val sensorOrientation: Int, val front: Boolean)

    private val rtp = RtpSocket(peerIp, port)
    private val scope = netScope()
    private val camThread = HandlerThread("ptt-cam").apply { start() }
    private val camHandler = Handler(camThread.looper)

    /** 实际生效的分辨率与帧率（编码器选定后回填，解码器必须与之保持一致）。 */
    private var W = 640
    private var H = 480
    private var FPS = 20

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var encInputSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    /** 本机编码器的参数集（SPS/PPS），周期性重发给对端。 */
    @Volatile private var csdList: List<ByteArray> = emptyList()

    /**
     * 解码器输入队列。
     * ★ 修复：解码器用 setCallback 进入**异步模式**，此时不允许再调 dequeueInputBuffer
     *   （会抛 IllegalStateException，把接收协程直接打死 → 只有本地预览）。
     *   现在改成「回调给出空闲缓冲 → 我们从队列里灌数据」，异步模式下完全合法。
     */
    private class DecFrame(val data: ByteArray, val flags: Int)

    private val inLock = Any()
    private val pendingFrames = ArrayDeque<DecFrame>()
    private val freeInputs = ArrayDeque<Int>()

    /** 对端发来的参数集，关键帧前先喂给解码器。 */
    private val configQueue = ArrayDeque<ByteArray>()

    fun start() {
        startEncoder()
        startDecoder()
        openCamera()
        scope.launch { rtp.receive { marker, payload -> onPacket(marker, payload) } }
        // 周期性重发参数集 + 要关键帧：首包丢失或换分辨率后，对端也能尽快恢复画面
        scope.launch {
            while (isActive) {
                delay(2000)
                csdList.forEach { rtp.send(VideoMarker.CONFIG, it) }
                requestKeyFrame()
            }
        }
    }

    // ---------- 编码侧：Camera2 -> Surface -> H.264 ----------

    private fun startEncoder() {
        var lastErr: Throwable? = null
        for (c in CANDIDATES) {
            val w = c[0]; val h = c[1]; val fps = c[2]
            var codec: MediaCodec? = null
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, if (w <= 320) 400_000 else 800_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // 1s 一个关键帧
                    // ★ CBR：华为/麒麟等编码器在 VBR 下可能根本不出帧
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                    // ★ Baseline：避免默认产出 High profile 让老机型解码器解不动
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    )
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        if (h <= 240) MediaCodecInfo.CodecProfileLevel.AVCLevel12
                        else MediaCodecInfo.CodecProfileLevel.AVCLevel31
                    )
                }
                val c2 = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec = c2
                c2.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val input = c2.createInputSurface()
                c2.setCallback(encCallback, camHandler)
                c2.start()
                encoder = c2
                encInputSurface = input
                W = w; H = h; FPS = fps
                Log.i(TAG, "编码器就绪 ${w}x${h}@${fps} Baseline/CBR")
                return
            } catch (e: Throwable) {
                lastErr = e
                Log.w(TAG, "编码器 ${w}x${h}@${fps} 不可用：${e.message}")
                // 失败的 codec 必须释放，否则反复重试会耗尽编解码资源
                runCatching { codec?.release() }
            }
        }
        Log.e(TAG, "全部编码参数都无法使用", lastErr)
        onError?.invoke("视频在这台手机上用不了（编码器不支持）")
    }

    private val encCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, idx: Int) {
            // Surface 输入模式下不会回调；保留空实现
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
            val buf: ByteBuffer? = codec.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val data = ByteArray(info.size)
                buf.get(data)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (!isConfig) {
                    rtp.send(if (isKey) VideoMarker.IDR else VideoMarker.NON_KEY, data)
                }
            }
            codec.releaseOutputBuffer(idx, false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // 取出参数集 SPS/PPS 发给对端（对端据此初始化解码器）
            val list = ArrayList<ByteArray>(2)
            format.getByteBuffer("csd-0")?.toBytes()?.let { list += it }
            format.getByteBuffer("csd-1")?.toBytes()?.let { list += it }
            csdList = list
            list.forEach { rtp.send(VideoMarker.CONFIG, it) }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
        }
    }

    /** 周期性向编码器要关键帧（API 26+）。 */
    private fun requestKeyFrame() {
        val enc = encoder ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching {
                enc.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }
    }

    private fun openCamera() {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: run {
            onError?.invoke("这个手机没有相机")
            return
        }
        val camId = pickCamera(ctx)?.id ?: run {
            Log.e(TAG, "no camera")
            onError?.invoke("找不到可用的相机")
            return
        }
        runCatching {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createCaptureSession(device)
                }

                override fun onDisconnected(device: CameraDevice) {
                    runCatching { device.close() }
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error")
                    runCatching { device.close() }
                    cameraDevice = null
                    onError?.invoke("相机打不开（错误码 $error）")
                }
            }, camHandler)
        }.onFailure {
            Log.e(TAG, "openCamera failed", it)
            onError?.invoke("相机打不开：${it.message}")
        }
    }

    /**
     * 先尝试「本地预览 + 编码器」双输出；不被支持时降级为只输出给编码器
     * —— 宁可没有本地小窗，也要保证对方能看到画面。
     */
    private fun createCaptureSession(device: CameraDevice) {
        val enc = encInputSurface ?: return
        trySession(device, listOf(previewSurface, enc)) { ok ->
            if (ok) return@trySession
            Log.w(TAG, "双 Surface 预览不被支持，降级为只编码")
            trySession(device, listOf(enc)) { ok2 ->
                if (!ok2) onError?.invoke("相机画面启动失败")
            }
        }
    }

    @Suppress("DEPRECATION") // 会话配置在 API 30 起有 SessionConfiguration 版本，minSdk 24 保留旧签名
    private fun trySession(
        device: CameraDevice,
        surfaces: List<Surface>,
        onDone: (Boolean) -> Unit
    ) {
        runCatching {
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        onDone(startPreview(device, session, surfaces))
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "capture session configure failed size=${surfaces.size}")
                        onDone(false)
                    }
                },
                camHandler
            )
        }.onFailure {
            Log.e(TAG, "createCaptureSession failed", it)
            onDone(false)
        }
    }

    private fun startPreview(
        device: CameraDevice,
        session: CameraCaptureSession,
        targets: List<Surface>
    ): Boolean = runCatching {
        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            .apply {
                targets.forEach { addTarget(it) }
                // 帧率区间只在摄像头确实支持时才下发，否则整条预览请求会被拒绝
                pickFpsRange(device)?.let {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            .build()
        session.setRepeatingRequest(req, null, camHandler)
        true
    }.onFailure { Log.e(TAG, "setRepeatingRequest failed", it) }.getOrDefault(false)

    /** 挑一个摄像头支持的、包含目标帧率的区间；找不到合适的就不设置。 */
    private fun pickFpsRange(device: CameraDevice): Range<Int>? = runCatching {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val ch = cm?.getCameraCharacteristics(device.id)
        val ranges = ch?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ranges?.firstOrNull { FPS in it.lower..it.upper } ?: ranges?.firstOrNull()
    }.onFailure { Log.w(TAG, "读取摄像头帧率范围失败", it) }.getOrNull()

    // ---------- 解码侧：RTP -> H.264 -> Surface ----------

    private fun startDecoder() {
        try {
            // 必须用编码器实际选用的分辨率配置解码器，否则帧尺寸不匹配导致不渲染
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H)
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(fmt, remoteSurface, null, 0)
                setCallback(decCallback, camHandler)
                start()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "decoder start failed", e)
            onError?.invoke("视频解码失败：${e.message}")
        }
    }

    private val decCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, idx: Int) {
            synchronized(inLock) {
                freeInputs.addLast(idx)
                drainLocked(codec)
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, idx: Int, info: MediaCodec.BufferInfo) {
            // true = 渲染到 configure 时传入的 remoteSurface
            runCatching { codec.releaseOutputBuffer(idx, true) }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "decoder error", e)
        }
    }

    private fun onPacket(marker: Int, payload: ByteArray) {
        when (marker) {
            VideoMarker.CONFIG -> {
                // 同一个参数集会被周期性重发，去重后再排队
                if (configQueue.none { it.contentEquals(payload) }) {
                    configQueue.addLast(payload)
                    while (configQueue.size > 4) configQueue.removeFirst()
                }
            }
            VideoMarker.IDR -> {
                // 关键帧前补喂参数集：首包丢失/切换分辨率后解码器才能恢复
                while (configQueue.isNotEmpty()) {
                    queueInput(configQueue.removeFirst(), MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                }
                queueInput(payload, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            }
            else -> queueInput(payload, 0)
        }
    }

    /** 排队一帧，交给解码器的空闲输入缓冲（异步模式下只能 queueInputBuffer）。 */
    private fun queueInput(payload: ByteArray, flags: Int) {
        val d = decoder ?: return
        synchronized(inLock) {
            // 解码跟不上时别无限堆积，否则画面延迟越拖越长
            while (pendingFrames.size > 60) pendingFrames.removeFirst()
            pendingFrames.addLast(DecFrame(payload, flags))
            drainLocked(d)
        }
    }

    private fun drainLocked(codec: MediaCodec) {
        while (freeInputs.isNotEmpty() && pendingFrames.isNotEmpty()) {
            val idx = freeInputs.removeFirst()
            val frame = pendingFrames.removeFirst()
            val ib = codec.getInputBuffer(idx) ?: continue
            ib.clear()
            if (frame.data.size > ib.remaining()) {
                // 单帧超过输入缓冲（超大帧）时丢弃，避免 BufferOverflowException
                Log.w(TAG, "视频帧过大丢弃 ${frame.data.size}")
                runCatching { codec.queueInputBuffer(idx, 0, 0, 0, frame.flags) }
                continue
            }
            ib.put(frame.data)
            runCatching { codec.queueInputBuffer(idx, 0, frame.data.size, 0, frame.flags) }
        }
    }

    fun stop() {
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.close() }; captureSession = null
        runCatching { cameraDevice?.close() }; cameraDevice = null
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
        runCatching { decoder?.stop() }; runCatching { decoder?.release() }; decoder = null
        runCatching { encInputSurface?.release() }; encInputSurface = null
        rtp.close()
        scope.cancel()
        camThread.quitSafely()
    }
}

/** ByteBuffer 当前剩余内容拷贝为 ByteArray。 */
private fun ByteBuffer.toBytes(): ByteArray {
    val b = ByteArray(remaining())
    get(b)
    return b
}
