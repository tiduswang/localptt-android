package com.example.localptt.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import kotlinx.coroutines.launch

/**
 * 语音对讲页：按住「说话」按钮采集并广播，松开停止；同时持续播放收到的语音。
 * 复用 AppBus.bus（UDP 语音总线），与主机/客户端身份无关。
 *
 * ★ 音量修复：AudioEngine 强制走扬声器（MODE_IN_COMMUNICATION + isSpeakerphoneOn），
 *   并在此基础上做软件增益；右上角「🔊 大喇叭」按钮可随时切回听筒。
 * ★ 同步：本页进入/退出会广播 FeatureSync，对端自动进/出同一页面。
 */
class VoiceActivity : AppCompatActivity() {

    private var engine: AudioEngine? = null
    private lateinit var btnTalk: View
    private lateinit var tvHint: TextView
    private lateinit var tvTalkIcon: TextView
    private lateinit var tvSpeakerIcon: TextView
    private lateinit var tvSpeakerText: TextView
    private lateinit var tvMuteIcon: TextView
    private lateinit var tvMuteText: TextView

    /** 是否静音（不听对方）。默认开声音。 */
    private var muted = false

    /** 是否由对端同步关闭（避免回发 exit 造成双方来回关闭）。 */
    private var closedByRemote = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice)
        AppBus.activeFeature = Feature.VOICE

        btnTalk = findViewById<View>(R.id.btnTalk)
        tvHint = findViewById(R.id.tvHint)
        tvTalkIcon = findViewById(R.id.tvTalkIcon)
        tvSpeakerIcon = findViewById(R.id.tvSpeakerIcon)
        tvSpeakerText = findViewById(R.id.tvSpeakerText)
        tvMuteIcon = findViewById(R.id.tvMuteIcon)
        tvMuteText = findViewById(R.id.tvMuteText)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnSpeaker).setOnClickListener {
            val e = engine
            if (e == null) { toast("还没连上，先回首页建房间吧"); return@setOnClickListener }
            val on = !e.speakerphone
            e.setSpeaker(on)
            renderSpeaker(on)
        }

        // 手动打开/关闭「听到对方的声音」
        findViewById<View>(R.id.btnMute).setOnClickListener {
            muted = !muted
            engine?.setPlaybackMuted(muted)
            renderMute()
            toast(if (muted) "已经把对方的声音关掉啦" else "又能听到对方说话啦")
        }

        val bus = AppBus.bus
        if (bus == null) {
            tvHint.text = "还没连上小伙伴，先回首页开房间吧"
            tvTalkIcon.text = "🚫"
            btnTalk.isEnabled = false
        }

        btnTalk.setOnTouchListener { _, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    engine?.pushToTalk(true)
                    tvHint.text = "📣 正在说话…"
                    tvTalkIcon.text = "🔊"
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    engine?.pushToTalk(false)
                    tvHint.text = "按住上面的按钮说话"
                    tvTalkIcon.text = "😴"
                }
            }
            false
        }

        // 对端退出本功能 → 我也退出
        lifecycleScope.launch {
            AppBus.featureEvents.collect { s ->
                if (s.feature == Feature.VOICE && s.action == Feature.EXIT) {
                    closedByRemote = true
                    finish()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureAudioPerm {
            val bus = AppBus.bus ?: return@ensureAudioPerm
            if (engine != null) return@ensureAudioPerm
            engine = AudioEngine(this, bus, useOpus = false).also {
                // 设备不支持编解码器/录音时降级提示，而不是崩溃
                if (!it.start()) {
                    tvHint.text = "手机麦克风不好用了，换个手机试试"
                    tvTalkIcon.text = "🚫"
                    btnTalk.isEnabled = false
                } else {
                    it.setPlaybackMuted(muted)
                    renderSpeaker(it.speakerphone)
                    renderMute()
                    tvHint.text = "按住上面的按钮说话"
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        engine?.pushToTalk(false)
        engine?.release()
        engine = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AppBus.activeFeature == Feature.VOICE) AppBus.activeFeature = null
        // 告诉房间里的小伙伴：我也退出语音页了（对端会同步退出）
        if (!closedByRemote) {
            runCatching { AppBus.postFeatureExit(Feature.VOICE) }
        }
    }

    private fun renderSpeaker(on: Boolean) {
        tvSpeakerIcon.text = if (on) "🔊" else "📞"
        tvSpeakerText.text = if (on) "大喇叭：开" else "小听筒：开"
        tvHint.text = if (on) "声音会从大喇叭放出来" else "声音从小听筒放（贴近耳朵）"
    }

    private fun ensureAudioPerm(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) action()
        else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2001)
            Toast.makeText(this, "需要录音权限才能对讲", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val bus = AppBus.bus ?: return
            if (engine != null) return
            engine = AudioEngine(this, bus, useOpus = false).also {
                if (!it.start()) {
                    tvHint.text = "手机麦克风不好用了，换个手机试试"
                    tvTalkIcon.text = "🚫"
                    btnTalk.isEnabled = false
                } else {
                    it.setPlaybackMuted(muted)
                    renderSpeaker(it.speakerphone)
                    renderMute()
                }
            }
        }
    }

    private fun renderMute() {
        tvMuteIcon.text = if (muted) "🔇" else "🎧"
        tvMuteText.text = if (muted) "听对方：关" else "听对方：开"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
