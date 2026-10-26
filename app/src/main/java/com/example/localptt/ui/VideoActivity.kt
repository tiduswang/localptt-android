package com.example.localptt.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.TextureView
import android.view.SurfaceView
import android.view.View
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 视频对讲页：Camera2 本地预览 + H.264 编码经 RTP 发给对端；对端视频解码后渲染到 SurfaceView。
 * 通话期间同时开启 [AudioEngine] 免提双向语音（麦克风 + 扬声器）。
 *
 * ★ 历史 Bug 修复（「视频用不了，摄像头麦克风都没启用」）：
 *  1. Camera2/MediaCodec 需要两个 Surface 都就绪才能建会话，过去没等 SurfaceView 的
 *     holder 回调就 configure，直接抛异常 → 相机根本没打开；现在等两个 Surface 都就绪。
 *  2. 过去必须手动点「邀请」，且对端弹窗才接通，两端状态很容易对不上；
 *     现在**进入页面即自动呼叫**，收到邀请时若已在视频页就自动接通。
 *  3. 视频通话过去**完全没有声音**（只传画面）；现在叠加免提语音通道。
 */
class VideoActivity : AppCompatActivity() {

    private lateinit var localPreview: TextureView
    private lateinit var remoteView: SurfaceView
    private lateinit var tvStatus: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        AppBus.activeFeature = Feature.VIDEO

        localPreview = findViewById(R.id.localPreview)
        remoteView = findViewById(R.id.remoteView)
        tvStatus = findViewById(R.id.tvVideoStatus)

        findViewById<View>(R.id.btnVideoBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnInvite).setOnClickListener { inviteFirstPeer() }

        // ① 等两个 Surface 都就绪再建会话（Camera2 必须拿到有效 Surface）
        localPreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                localReady = true
                tryAttach()
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                localReady = false
                return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        if (localPreview.isAvailable) localReady = true

        remoteView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { remoteReady = true; tryAttach() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { remoteReady = false }
        })
        if (remoteView.holder.surface?.isValid == true) remoteReady = true

        // ② 订阅来电
        lifecycleScope.launch {
            AppBus.videoInbound.collect { onSignaling(it) }
        }
        // ③ 对端退出 → 我也退出
        lifecycleScope.launch {
            AppBus.featureEvents.collect { s ->
                if (s.feature == Feature.VIDEO && s.action == Feature.EXIT) {
                    closedByRemote = true
                    finish()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        resumed = true
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

    override fun onDestroy() {
        super.onDestroy()
        if (AppBus.activeFeature == Feature.VIDEO) AppBus.activeFeature = null
        if (!closedByRemote) {
            runCatching { AppBus.postFeatureExit(Feature.VIDEO) }
        }
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
        val st = localPreview.surfaceTexture ?: return
        val remote = remoteView.holder.surface
        if (!remote.isValid) return
        try {
            val preview = android.view.Surface(st)
            session = VideoCallSession(this, ip, preview, remote) { err ->
                runOnUiThread {
                    toast(err)
                    tvStatus.text = "🚫 $err"
                }
            }.also { it.start() }
            inCall = true
            tvStatus.text = "📹 正在一起看～（麦克风已开）"
        } catch (e: Throwable) {
            // Camera2 / MediaCodec 在部分机型上不可用：提示而不是崩溃
            android.util.Log.w("LocalPTT", "视频会话启动失败", e)
            toast("相机用不了：${e.message}")
            tvStatus.text = "🚫 视频用不了"
        }
    }

    /** 视频通话的双向语音：免提常开（麦克风 + 扬声器）。 */
    private fun startAudio() {
        if (audio != null) return
        val bus = AppBus.bus ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            audio = AudioEngine(this, bus, useOpus = false).also {
                if (it.start()) {
                    it.setSpeaker(true)
                    it.startHandsFree()
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

    private fun id() = System.currentTimeMillis().toString().takeLast(8)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
