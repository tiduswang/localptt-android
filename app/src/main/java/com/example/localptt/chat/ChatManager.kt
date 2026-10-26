package com.example.localptt.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import com.example.localptt.AppBus
import com.example.localptt.data.*
import com.example.localptt.net.lanIpv4
import com.example.localptt.net.localIpv4
import com.example.localptt.net.netScope
import com.example.localptt.net.p2pIpv4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 简易微聊：文字 / 表情 / 语音消息 / 照片。
 *
 * 传输策略：
 *  - 文字、表情、照片索引：直接走 TCP 信令（[ChatMsg]），可靠且可广播；
 *  - 语音消息：录成 m4a 落盘，由**发送方自己**的 [VoiceFileServer] 提供 HTTP 下载（端口 8899），
 *    信令里只带 [VoiceMeta]（时长 + http url），接收方按需下载后播放。
 *
 * ★ 历史 Bug 修复（微聊消息对方收不到）：
 *  1. 本类改为进程内单例（App.onCreate 创建），不再随建网/重连重建 —— 功能页订阅的 Flow
 *     过去指向已被替换的旧实例，网络看着是连着的，消息却一条也收不到。
 *  2. 发送时**动态**解析当前传输层（AppBus.post），不再在构造时捕获 client/server，
 *     因此掉线重连后无需重新创建本类也能继续收发。
 *  3. 语音消息 URL 指向发送方自己的 IP（每台设备都起 VoiceFileServer），
 *     过去固定指向主机 IP，导致客户端发的语音主机下载不到。
 */
class ChatManager(private val ctx: Context) {

    /**
     * 收到的微聊消息（ChatMsg / VoiceMeta）。
     *
     * ★ 必须是 SharedFlow，不能是 Channel：
     *   Channel + receiveAsFlow() 是**单消费者**的，一条消息只会被众多收集者中的某一个取走。
     *   一旦页面上同时存在两个收集者（典型场景：功能同步又打开了一次微聊页），
     *   消息就会被「已经看不见的那个页面」截走，表现为「对方发的消息这边死活不显示」。
     *   SharedFlow 会广播给所有收集者，并保留最近若干条给晚绑定的页面补显示。
     */
    private val _inbound = MutableSharedFlow<Msg>(replay = 64, extraBufferCapacity = 64)
    val inbound: SharedFlow<Msg> = _inbound

    private val scope = netScope()
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceStartAt = 0L

    private val voiceDir: File get() = File(ctx.cacheDir, "voice").apply { mkdirs() }

    fun received(): Flow<Msg> = inbound

    /** @return 是否真的发出去了（没建网时返回 false，UI 据此提示）。 */
    fun sendText(to: String, text: String) = emit(ChatMsg(myId(), to, uid(), "text", text, now()))
    fun sendEmoji(to: String, emoji: String) = emit(ChatMsg(myId(), to, uid(), "emoji", emoji, now()))
    fun sendPhoto(to: String, uri: Uri) =
        emit(ChatMsg(myId(), to, uid(), "photo", uri.toString(), now()))

    /** 开始录制语音消息（无录音权限或设备不支持时静默失败，不崩溃）。 */
    fun startVoice() {
        runCatching {
            voiceFile = File(voiceDir, "voice_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")     // MediaRecorder() 在 API 31 弃用，minSdk 24 需保留
            val r = MediaRecorder()
            recorder = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(voiceFile!!.absolutePath)
            r.prepare()
            r.start()
            voiceStartAt = System.currentTimeMillis()
        }.onFailure {
            Log.w("LocalPTT", "语音消息录制失败：${it.message}")
            runCatching { recorder?.release() }
            recorder = null
        }
    }

    /** 停止录制并发送（返回时长 ms；0 表示录音失败）。 */
    fun stopVoiceAndSend(to: String): Long {
        runCatching { recorder?.stop() }.onFailure { Log.w("LocalPTT", "停止录音失败：${it.message}") }
        runCatching { recorder?.release() }
        recorder = null
        val dur = (System.currentTimeMillis() - voiceStartAt).coerceAtLeast(0)
        val f = voiceFile ?: return 0L
        voiceFile = null
        if (!f.exists() || f.length() <= 0) return 0L
        return if (emit(VoiceMeta(myId(), to, uid(), dur, voiceUrl(f)))) dur else 0L
    }

    fun cancelVoice() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        voiceFile?.let { runCatching { it.delete() } }
        voiceFile = null
    }

    /** 下载对端语音消息到本地缓存。 */
    fun downloadVoice(url: String, onReady: (File?) -> Unit) {
        scope.launch {
            val f = runCatching {
                val out = File(voiceDir, "dl_${System.currentTimeMillis()}.m4a")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { input.copyTo(it) }
                }
                conn.disconnect()
                out
            }.onFailure { Log.w("LocalPTT", "语音下载失败：${it.message}") }.getOrNull()
            withContext(Dispatchers.Main) { onReady(f) }
        }
    }

    /** 信令分发入口（由 MainActivity.dispatch 调用）。 */
    fun onMessage(m: Msg) {
        if (m.from == myId()) return          // 不重复回显自己发的消息
        if (m is ChatMsg || m is VoiceMeta) _inbound.tryEmit(m)
    }

    /** 发送：动态解析当前传输层；同时本地回显，保证发送方 UI 立即显示。 */
    private fun emit(m: Msg): Boolean {
        val ok = (AppBus.client != null || AppBus.server != null)
        if (ok) AppBus.post(m)
        _inbound.tryEmit(m)         // 本地回显，发送方立刻看到自己发的消息
        return ok
    }

    /** 语音文件对外 URL：**发送方自己**的 IP（每台设备都起 VoiceFileServer）。 */
    private fun voiceUrl(f: File): String {
        // 直连模式必须给对端 p2p 网段地址；局域网模式优先 wlan，最后才退到任意网卡
        val myIp = lanIpv4() ?: p2pIpv4() ?: localIpv4() ?: "127.0.0.1"
        return "http://$myIp:${VoiceFileServer.PORT}/${f.name}"
    }

    private fun myId() = AppBus.myId
    private fun now() = System.currentTimeMillis()
    private fun uid() = UUID.randomUUID().toString().take(8)
}
