package com.example.localptt.ui

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.localptt.AppBus
import com.example.localptt.R
import com.example.localptt.audio.AudioEngine
import com.example.localptt.data.Feature
import com.example.localptt.data.VideoCall
import com.example.localptt.video.VideoCallSession
import kotlinx.coroutines.launch

/**
 * 视频对讲页：Camera2 本地预览 + H.264 编码经 RTP 发给对端；对端视频解码后渲染到 TextureView。
 * 通话期间同时开启 [AudioEngine] 免提双向语音（麦克风可手动开关）。
 *
 * ★ v1.3.2 画面方向与全屏：
 *  - 远端画面改 TextureView（SurfaceView 不支持可靠的旋转/缩放），
 *    「🔄 转对方画面」按 90° 循环，解决「一台横、一台竖」的画面方向不一致（选择会记住）；
 *  - 「⛶ 全屏」沉浸式全屏（横屏、隐藏上下按钮栏），此时自己的画面缩成左上角画中画；
 *    1.3.9 起：点大屏 = 把左上角小窗转 90°（不再进全屏，全屏只走按钮）；
 *  - 「🎤 麦克风」手动开关，关掉后不再把采集到的声音发给对方。
 *
 * ★ 历史 Bug 修复（「视频用不了，摄像头麦克风都没启用」）：
 *  1. Camera2/MediaCodec 需要两个 Surface 都就绪才能建会话；现在等两个 Surface 都就绪。
 *  2. 过去必须手动点「邀请」，且对端弹窗才接通；现在进入页面即自动呼叫。
 *  3. 视频通话过去完全没有声音；现在叠加免提语音通道。
 */
class VideoActivity : AppCompatActivity() {

    private lateinit var localPreview: TextureView
    private lateinit var remoteView: TextureView
    private lateinit var tvStatus: TextView
    private lateinit var videoStage: FrameLayout
    private lateinit var localCard: FrameLayout
    private lateinit var videoContent: View
    private lateinit var btnRotate: TextView
    private lateinit var btnFullscreen: TextView
    private lateinit var btnMic: TextView

    private var session: VideoCallSession? = null
    private var audio: AudioEngine? = null
    private var peerIp: String? = null
    private var inCall = false

    @Volatile private var localReady = false
    @Volatile private var remoteReady = false

    /** 本页是否在前台（用于决定收到邀请时自动接通还是弹窗）。 */
    @Volatile private var resumed = false

    /** 是否由对端同步关闭。 */
    private var closedByRemote = false

    /** 对端是否也在这个功能页里出现过。 */
    private var peerWasHere = false

    // ---------- 画面偏好（持久化） ----------

    /** 画面旋转角度（0/90/180/270），两端各自保存自己的偏好。 */
    private var videoRotation = 0

    /**
     * 小窗（画中画）方向的手动修正（0/90/180/270）。
     * 有些机型上报的相机 sensor 方向不准，自动摆正会失效（人还是横着），
     * 所以点一下大屏就把小窗转 90°，转到孩子看着是正的为止，选择会记住。
     */
    private var pipRotationOffset = 0

    /** 是否处于沉浸式全屏。 */
    private var fullscreen = false

    /** 麦克风是否打开（关掉后不把声音发给对方）。 */
    private var micOn = true

    // ---------- 相机画面方向（本地画中画用） ----------

    /** 相机 sensor 方向（多数机型前置是 270°）。 */
    private var camSensor = 90

    /** 是否前置相机（前置要镜像显示，像个镜子）。 */
    private var camFront = true

    private val prefs get() = getSharedPreferences("localptt", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        AppBus.activeFeature = Feature.VIDEO

        // 视频通话期间不要让屏幕自动熄灭（看孩子/看对方时不可能一直戳屏幕）。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        localPreview = findViewById(R.id.localPreview)
        remoteView = findViewById(R.id.remoteView)
        tvStatus = findViewById(R.id.tvVideoStatus)
        videoStage = findViewById(R.id.videoStage)
        localCard = findViewById(R.id.localCard)
        videoContent = findViewById(R.id.videoContent)
        btnRotate = findViewById(R.id.btnRotate)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnMic = findViewById(R.id.btnMic)

        // 顶部实时信号（我 / 对方）
        findViewById<TextView>(R.id.tvLink).bindLinkQuality(this)

        videoRotation = prefs.getInt("videoRotation", 0)
        pipRotationOffset = prefs.getInt("pipRotationOffset", 0)
        readCameraInfo()

        findViewById<View>(R.id.btnVideoBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnInvite).setOnClickListener { inviteFirstPeer() }
        btnRotate.setOnClickListener { rotateVideo() }
        btnFullscreen.setOnClickListener { setFullscreen(!fullscreen) }
        btnMic.setOnClickListener { toggleMic() }
        // 点大屏 = 切换左上角小窗的方向（不再进全屏，全屏只走「⛶ 全屏」按钮）
        remoteView.setOnClickListener { rotatePip() }
        // 小窗右上角的 🔄 按钮也一样（更明显，孩子知道点哪里）
        findViewById<View>(R.id.btnPipRotate).setOnClickListener { rotatePip() }

        // ① 等两个 Surface 都就绪再建会话（Camera2 必须拿到有效 Surface）
        localPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                localReady = true
                applyLocalPreviewTransform()
                tryAttach()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                // 相机可能把预览缓冲改成横画幅的尺寸：变换矩阵要跟着重算
                applyLocalPreviewTransform()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                localReady = false
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (localPreview.isAvailable) localReady = true

        // 小窗尺寸变化（换横竖比例 / 进出全屏）后重新摆正自己的画面
        localPreview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyLocalPreviewTransform()
        }

        remoteView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                remoteReady = true
                tryAttach()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // 远端 Surface 被系统回收：先停会话，重建后再接上（否则解码器往失效 Surface 写）
                remoteReady = false
                stopCall()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (remoteView.isAvailable) remoteReady = true

        // 尺寸变化（横竖屏切换）后重新计算旋转画面的布局尺寸
        videoStage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fitRotatedRemote()
            applyRotation()
        }

        // ② 订阅来电
        lifecycleScope.launch {
            AppBus.videoInbound.collect { onSignaling(it) }
        }
        // ③ 对端退出 → 我也退出（exit 事件 / 对端进了别的功能 / 对端曾在这里、现在回了首页）
        lifecycleScope.launch {
            AppBus.featureEvents.collect { s ->
                if (s.from == AppBus.myId) return@collect
                if (s.feature == Feature.VIDEO && s.action != Feature.EXIT) peerWasHere = true
                val explicitExit = s.action == Feature.EXIT && s.feature == Feature.VIDEO
                val peerEnteredOther = s.action == Feature.ENTER && s.feature != Feature.VIDEO
                val peerWentHome = s.action == Feature.STATE && s.feature.isBlank() && peerWasHere
                if (explicitExit || peerEnteredOther || peerWentHome) {
                    closedByRemote = true
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 重新声明「我在视频页」，从别的页面返回 / 全屏切换后状态都不会错
        AppBus.activeFeature = Feature.VIDEO
    }

    override fun onStart() {
        super.onStart()
        resumed = true
        applyOrientation()
        applyRotation()
        updateMicButton()
        ensureCameraPerm {
            val peer = AppBus.others.firstOrNull()
            if (peer == null) {
                tvStatus.text = "🕐 房间里还没有小伙伴，先回首页连上他吧"
                return@ensureCameraPerm
            }
            peerIp = peer.ip
            // 进入页面即自动呼叫：对端收到后会同步进入本页并自动接通
            send(VideoCall(AppBus.myId, peer.id, id(), "invite"))
            tvStatus.text = "📤 正在叫 ${peer.name} 一起看…"
            tryAttach()
            startAudio()
        }
    }

    override fun onStop() {
        super.onStop()
        resumed = false
        stopAudio()
        sendBye()
        stopCall()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换不重建 Activity（见 manifest configChanges），这里重算画面尺寸
        applyRotation()
        if (fullscreen) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AppBus.activeFeature == Feature.VIDEO) AppBus.activeFeature = null
        if (!closedByRemote) {
            runCatching { AppBus.postFeatureExit(Feature.VIDEO) }
        }
    }

    // ---------- 画面：转向 / 横竖屏 / 全屏 ----------

    /** 「↻ 转画面」：0 -> 90 -> 180 -> 270 -> 0，两端画面方向保持一致，选择会记住。 */
    private fun rotateVideo() {
        videoRotation = (videoRotation + 90) % 360
        prefs.edit().putInt("videoRotation", videoRotation).apply()
        applyRotation()
        toast("画面已旋转到 $videoRotation°")
    }

    /** 点大屏（或小窗角的 🔄）：把小窗方向转 90°，选择会记住。人不正就再点一下。 */
    private fun rotatePip() {
        pipRotationOffset = (pipRotationOffset + 90) % 360
        prefs.edit().putInt("pipRotationOffset", pipRotationOffset).apply()
        applyRotation()
        toast("小画面 ${pipAngle()}°，人不正就再点一下")
    }

    /**
     * 小窗最终角度 = 相机自动摆正 + 手动点出来的修正。
     *
     * ★ 1.4.1 硬保证：竖屏页面下小窗内容**必须**转 90°/270°。
     *   相机原始画幅是横的，不转人必歪；但有些机型把 sensor 方向报成 0°，
     *   自动摆正会漏掉这一步（照片实证：小窗横着、人侧躺）。
     *   所以竖屏页面算出 0°/180° 时强制补 90°，这样点屏只会在
     *   「正 / 倒」两个竖直状态间切换，再也不会出现横躺。
     */
    private fun pipAngle(): Int {
        var a = (sensorCorrection() + pipRotationOffset) % 360
        if (!fullscreen && displayRotationDegrees() % 180 == 0 && a % 180 == 0) {
            a = (a + 90) % 360
        }
        return a
    }

    /** 把旋转与「补齐尺寸」同时应用到两块画面：转 90/270 时宽高交换，否则会被裁掉。 */
    private fun applyRotation() {
        // 远端画面整体旋转（对端发来的原样画面），自己小窗则按 sensor 方向单独摆正
        remoteView.rotation = videoRotation.toFloat()
        btnRotate.text = "🔄 转画面 ${videoRotation}°"

        // 自己的小窗：按「自动摆正 + 手动修正」的最终角度决定竖窗还是横窗。
        // ★ 1.4.0：小窗严格用 4:3（和相机 640×480 画幅一致），
        //   TextureView 是拉伸铺满的，窗口不是 4:3 人就会被拉扁/压长。
        val landscapePic = pipAngle() % 180 == 0
        val lp = localCard.layoutParams as FrameLayout.LayoutParams
        lp.width = dp(if (landscapePic) 144 else 108)
        lp.height = dp(if (landscapePic) 108 else 144)
        lp.gravity = Gravity.TOP or Gravity.START
        localCard.layoutParams = lp
        applyLocalPreviewTransform()
        fitRotatedRemote()
    }

    /** 读取相机方向信息（挑相机的逻辑与 [VideoCallSession] 保持一致）。 */
    private fun readCameraInfo() {
        val info = VideoCallSession.pickCamera(this) ?: return
        camSensor = info.sensorOrientation
        camFront = info.front
    }

    /** 当前屏幕方向（0/90/180/270）。 */
    private fun displayRotationDegrees(): Int {
        @Suppress("DEPRECATION")   // minSdk 24：display.rotation 要 API 30 才有
        val rot = if (android.os.Build.VERSION.SDK_INT >= 30) {
            display?.rotation ?: android.view.Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
        return when (rot) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * 相机 sensor 方向换算成「显示时需要转多少度」：
     *  - 后置：sensor 角度 - 屏幕旋转；
     *  - 前置：sensor 角度 + 屏幕旋转，再取反（前置画面是镜像的，Camera1 时代的经典公式）。
     * 例：多数手机前置 sensor=270、竖屏 → 90°，转 90° 人就是正的。
     */
    private fun sensorCorrection(): Int {
        val deg = displayRotationDegrees()
        return if (camFront) (360 - (camSensor + deg) % 360) % 360
        else (camSensor - deg + 360) % 360
    }

    /**
     * ★★ 1.4.3：小窗「旋转」和「不变形」同时成立的关键 —— 预览 View 永远保持
     *   相机缓冲的 4:3 横画幅（144×108dp），只在 View 层做旋转；
     *   小窗（localCard）则按角度换横竖（转了 90° 就变 108×144 竖窗）。
     *
     *   这样：拉伸比例恒为 1:1（144×108 里画 4:3 缓冲 = 不失真），
     *   旋转 90° 后正好铺满竖窗，横窗/竖窗都不会拉长或压扁人。
     *
     * ★ 为什么不再用 setTransform(Matrix)：在实测机型上矩阵只改了比例、
     *   旋转没有生效（照片实证：比例对了但人依旧横着）；而 View.rotation
     *   在 1.3.9 的反馈里确实能把人转正，只是当时尺寸算错导致拉扁。
     *
     * ★ 尺寸一律用 dp 常量，**不再读 localCard.width**：applyRotation() 刚改完
     *   layoutParams 时布局还没刷新，读到的是旧宽高 —— 这正是上一版「人被拉扁」
     *   的真正原因（竖窗 108×144 被套进旧尺寸里硬拉伸）。
     */
    private fun applyLocalPreviewTransform() {
        val angle = pipAngle()

        // 预览固定 4:3 横画幅（与 640×480 缓冲一致），居中，比例 1:1 绝不拉伸
        val w = dp(144)
        val h = dp(108)
        val lp = localPreview.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            localPreview.layoutParams = lp
        }

        localPreview.rotation = angle.toFloat()

        // 前置相机像照镜子一样水平镜像。Android 变换顺序是 scale→rotate，
        // 所以 0°/180° 要翻 X 轴，90°/270° 要翻 Y 轴，最终才是「水平镜像」。
        if (camFront) {
            if (angle % 180 == 0) { localPreview.scaleX = -1f; localPreview.scaleY = 1f }
            else                  { localPreview.scaleX = 1f;  localPreview.scaleY = -1f }
        } else {
            localPreview.scaleX = 1f; localPreview.scaleY = 1f
        }
    }

    /** 远端画面旋转 90/270 时要按舞台尺寸交换宽高，并居中，才能铺满不被裁。 */
    private fun fitRotatedRemote() {
        val lp = remoteView.layoutParams as FrameLayout.LayoutParams
        val pw = videoStage.width
        val ph = videoStage.height
        if (videoRotation % 180 == 0 || pw <= 0 || ph <= 0) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            lp.width = ph
            lp.height = pw
        }
        lp.gravity = Gravity.CENTER
        remoteView.layoutParams = lp
    }

    /**
     * 页面方向：只有全屏时才强制横屏，平时固定竖屏。
     * ★ 1.3.7 去掉了独立的「🔁 横屏」按钮：全屏本身就会横屏，两个开关放一起
     *   小朋友分不清「横屏」和「转画面」，容易以为点错了。
     */
    private fun applyOrientation(forceLandscape: Boolean = false) {
        requestedOrientation = if (forceLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * 全屏：沉浸式 + 横屏 + 隐藏顶部/底部按钮栏，自己的画面缩成左上角画中画。
     * 「⤢ 退出全屏」按钮可退出。
     */
    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        findViewById<View>(R.id.videoTopBar).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.videoBottomBar).visibility = if (on) View.GONE else View.VISIBLE
        val pad = if (on) 0 else dp(12)
        videoContent.setPadding(pad, pad, pad, pad)
        (videoContent as? ViewGroup)?.setBackgroundColor(
            if (on) 0xFF000000.toInt() else ContextCompat.getColor(this, R.color.kids_bg)
        )
        btnFullscreen.text = if (on) "⤢ 退出全屏" else "⛶ 全屏"
        if (on) {
            hideSystemBars()
            applyOrientation(forceLandscape = true)
        } else {
            showSystemBars()
            applyOrientation()
        }
    }

    @Suppress("DEPRECATION")   // minSdk 24：systemUiVisibility 在 API 30 才被 WindowInsetsController 取代
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    // ---------- 麦克风 ----------

    /** 手动开关麦克风：关掉后采集到的声音直接丢弃，对方听不见你（但还能听见对方）。 */
    private fun toggleMic() {
        micOn = !micOn
        audio?.setMicMuted(!micOn)
        updateMicButton()
        toast(if (micOn) "麦克风已打开" else "麦克风已关闭，对方听不见你")
    }

    private fun updateMicButton() {
        btnMic.text = if (micOn) "🎤 麦克风：开" else "🔇 麦克风：关"
        btnMic.setBackgroundResource(
            if (micOn) R.drawable.shape_pill_green else R.drawable.shape_pill_gray
        )
        btnMic.setTextColor(if (micOn) 0xFFFFFFFF.toInt() else 0xFF5A4A3A.toInt())
    }

    // ---------- 信令 ----------

    private fun inviteFirstPeer() {
        val peer = AppBus.others.firstOrNull()
        if (peer == null) {
            toast("还没看到小伙伴，先回首页连上他吧")
            return
        }
        peerIp = peer.ip
        send(VideoCall(AppBus.myId, peer.id, id(), "invite"))
        tvStatus.text = "📤 已经叫 ${peer.name} 一起看啦…"
        tryAttach()
        startAudio()
    }

    private fun onSignaling(m: VideoCall) {
        if (m.from == AppBus.myId) return
        when (m.action) {
            "invite" -> {
                AppBus.pendingVideoCaller = m.from
                peerIp = AppBus.peers.firstOrNull { it.id == m.from }?.ip ?: peerIp
                runOnUiThread {
                    if (resumed) {
                        // 已经在本页（多半是被对端同步进来的）→ 直接接通，孩子不用再点一次
                        send(VideoCall(AppBus.myId, m.from, id(), "accept"))
                        tvStatus.text = "📹 和 ${peerNameOf(m.from)} 一起看～"
                        ensureCameraPerm { tryAttach(); startAudio() }
                    } else {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("📹 视频邀请")
                            .setMessage("${peerNameOf(m.from)} 想和你一起看")
                            .setPositiveButton("好呀 ✅") { _, _ ->
                                send(VideoCall(AppBus.myId, m.from, id(), "accept"))
                                startCall()
                            }
                            .setNegativeButton("不要 ❌") { _, _ ->
                                send(VideoCall(AppBus.myId, m.from, id(), "reject"))
                            }
                            .show()
                    }
                }
            }
            "accept" -> runOnUiThread {
                tvStatus.text = "📹 小伙伴接啦，正在一起看～"
                ensureCameraPerm { tryAttach(); startAudio() }
            }
            "reject" -> runOnUiThread { tvStatus.text = "😢 小伙伴现在不想视频" }
            "bye" -> runOnUiThread { stopCall(); stopAudio(); tvStatus.text = "👋 拜拜，下次再聊" }
        }
    }

    private fun send(m: VideoCall) = AppBus.post(m)

    private fun sendBye() {
        val peer = AppBus.others.firstOrNull() ?: return
        runCatching { AppBus.post(VideoCall(AppBus.myId, peer.id, id(), "bye")) }
    }

    // ---------- 通话 ----------

    private fun startCall() {
        ensureCameraPerm {
            tvStatus.text = "📹 正在一起看～"
            tryAttach()
            startAudio()
        }
    }

    /** 两个 Surface 都就绪、且知道对端 IP，才真正启动 Camera2 + 编解码。 */
    private fun tryAttach() {
        val ip = peerIp
        if (ip == null) return
        if (inCall) return
        if (!localReady || !remoteReady) {
            android.util.Log.i("LocalPTT", "等待画面就绪 local=$localReady remote=$remoteReady")
            return
        }
        val localSt = localPreview.surfaceTexture ?: return
        val remoteSt = remoteView.surfaceTexture ?: return
        try {
            val preview = android.view.Surface(localSt)
            val remote = android.view.Surface(remoteSt)
            session = VideoCallSession(this, ip, preview, remote) { err ->
                runOnUiThread {
                    toast(err)
                    tvStatus.text = "🚫 $err"
                }
            }.also { it.start() }
            inCall = true
            tvStatus.text = if (micOn) "📹 正在一起看～（麦克风已开）" else "📹 正在一起看～（麦克风已关）"
        } catch (e: Throwable) {
            // Camera2 / MediaCodec 在部分机型上不可用：提示而不是崩溃
            android.util.Log.w("LocalPTT", "视频会话启动失败", e)
            toast("相机用不了：${e.message}")
            tvStatus.text = "🚫 视频用不了"
        }
    }

    /** 视频通话的双向语音：免提常开（麦克风可手动关，扬声器常开）。 */
    private fun startAudio() {
        if (audio != null) {
            audio?.setMicMuted(!micOn)
            return
        }
        val bus = AppBus.bus ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            audio = AudioEngine(this, bus, useOpus = false).also {
                if (it.start()) {
                    it.setSpeaker(true)
                    it.startHandsFree()
                    it.setMicMuted(!micOn)
                }
            }
        }.onFailure { android.util.Log.w("LocalPTT", "视频通话音频启动失败：${it.message}") }
    }

    private fun stopAudio() {
        runCatching { audio?.stopHandsFree() }
        runCatching { audio?.release() }
        audio = null
    }

    private fun stopCall() {
        runCatching { session?.stop() }
        session = null
        inCall = false
    }

    // ---------- 权限 ----------

    private fun ensureCameraPerm(action: () -> Unit) {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (need.isEmpty()) action()
        else {
            pendingAction = action
            ActivityCompat.requestPermissions(this, need.toTypedArray(), 2002)
        }
    }

    private var pendingAction: (() -> Unit)? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2002 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            val act = pendingAction
            pendingAction = null
            act?.invoke()
        }
    }

    /** 对端 id → 昵称，拿不到就直接显示 id。 */
    private fun peerNameOf(id: String): String =
        AppBus.peers.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun id() = System.currentTimeMillis().toString().takeLast(8)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
