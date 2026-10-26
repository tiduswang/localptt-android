package com.example.localptt.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localptt.AppBus
import com.example.localptt.R
import com.example.localptt.chat.ChatManager
import com.example.localptt.data.Avatars
import com.example.localptt.data.ChatMsg
import com.example.localptt.data.Feature
import com.example.localptt.data.Msg
import com.example.localptt.data.VoiceMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 微聊页：文字 / 表情 / 语音消息 / 照片。
 *
 * ★ 本轮修复（参考 RoseChat / WDCable_flutter）：
 *  1. **照片真的能收发**：过去只发了一个 `content://` 索引，对端看到的永远是「📷 [照片]」占位。
 *     现在发送前压成 ≤1280px JPEG（q80），和语音一样由本机文件服务提供下载，
 *     接收方收到后直接显示缩略图，点一下看大图。
 *  2. **消息去重**：按消息 id 去重，页面重进/流回放不会再出现重复气泡。
 *  3. **媒体缓存**：语音/照片下载一次后走本地缓存，重复点击不再重复下载；
 *     下载先写 `.part` 再改名，不会把半截文件当完整文件播放。
 *  4. 语音录制取消手势（ACTION_CANCEL）不再误发送。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var tvPeer: TextView
    private val adapter = ChatAdapter(
        onClick = { m -> onBubbleClick(m) },
        loadPhoto = { url, cb -> photoBitmap(url, cb) }
    )

    private var player: MediaPlayer? = null

    /** 照片位图缓存（同一张图既做缩略图也做点开的大图）。 */
    private val photoCache = HashMap<String, Bitmap>()

    /**
     * 消息目标：统一**广播给房间里所有人**，避免名单过期时主机查不到 id 而静默丢消息。
     */
    private val peerId: String get() = "all"

    private val chat: ChatManager? get() = AppBus.chat

    /** 是否由对端同步关闭。 */
    private var closedByRemote = false

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) sendPhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        // 由本页面自己声明「当前在这个功能里」，供对端的同步进入做去重判断
        AppBus.activeFeature = Feature.CHAT

        rv = findViewById(R.id.rvChat)
        etInput = findViewById(R.id.etInput)
        tvPeer = findViewById(R.id.tvChatPeer)

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        refreshPeerBar()

        findViewById<View>(R.id.btnChatBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnSend).setOnClickListener {
            val t = etInput.text.toString().trim()
            if (t.isNotEmpty() && send { chat?.sendText(peerId, t) }) etInput.setText("")
        }

        findViewById<View>(R.id.btnEmoji).setOnClickListener { sendEmoji() }
        findViewById<View>(R.id.btnPhoto).setOnClickListener { pickPhoto.launch("image/*") }

        val btnVoice = findViewById<View>(R.id.btnVoiceMsg)
        val tvVoiceIcon = findViewById<TextView>(R.id.tvVoiceIcon)
        val tvVoiceLabel = findViewById<TextView>(R.id.tvVoiceLabel)
        btnVoice.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    chat?.startVoice()
                    tvVoiceIcon.text = "🔴"
                    tvVoiceLabel.text = "松手发送"
                    true
                }
                MotionEvent.ACTION_UP -> {
                    send { chat?.stopVoiceAndSend(peerId) }
                    tvVoiceIcon.text = "🎤"
                    tvVoiceLabel.text = "按住说话"
                    toast("📤 语音发出去啦")
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 手指滑走 = 取消，不发送
                    chat?.cancelVoice()
                    tvVoiceIcon.text = "🎤"
                    tvVoiceLabel.text = "按住说话"
                    true
                }
                else -> false
            }
        }

        // 消息流：管理器没就绪时轮询等待，避免「打开太早导致再也收不到消息」
        lifecycleScope.launch {
            while (true) {
                val c = AppBus.chat
                if (c != null) {
                    runCatching {
                        c.received().collect { m -> onIncoming(m) }
                    }.onFailure { android.util.Log.w("LocalPTT", "聊天消息流异常：${it.message}") }
                }
                delay(500)
            }
        }

        // 对端退出 → 我也退出
        lifecycleScope.launch {
            AppBus.featureEvents.collect { s ->
                if (s.feature == Feature.CHAT && s.action == Feature.EXIT) {
                    closedByRemote = true
                    finish()
                }
            }
        }
    }

    /** 统一的发送入口：没建网时明确提示，不静默丢弃。 */
    private fun send(block: () -> Unit): Boolean {
        if (AppBus.client == null && AppBus.server == null) {
            toast("还没连上小伙伴，先回首页开房间吧")
            return false
        }
        if (chat == null) {
            toast("微聊还没准备好，稍等一下再试")
            return false
        }
        return runCatching { block() }.isSuccess
    }

    /** 照片压缩 + 发送都在后台做，避免大图解码卡住界面。 */
    private fun sendPhoto(uri: Uri) {
        if (AppBus.client == null && AppBus.server == null) {
            toast("还没连上小伙伴，先回首页开房间吧")
            return
        }
        toast("🖼️ 正在处理照片…")
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { chat?.sendPhoto(peerId, uri) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toast(if (ok == true) "📤 照片发出去啦" else "😢 这张照片处理不了")
            }
        }
    }

    private fun refreshPeerBar() {
        val peer = AppBus.others.firstOrNull()
        val name = peer?.name ?: "所有人（广播）"
        tvPeer.text = "💬 正在和 $name 聊天"
        findViewById<ImageView>(R.id.ivChatPeerAvatar)
            ?.setImageResource(Avatars.drawableOf(peer?.avatar ?: "bear"))
    }

    /** 表情面板：大号表情 + 文字说明，小朋友一看就懂。 */
    private fun sendEmoji() {
        val emojis = arrayOf("👍", "❤️", "😂", "😮", "😢", "🎉", "⭐", "👋")
        val labels = arrayOf("真棒", "喜欢", "太好笑", "哇！", "难过", "好耶", "厉害", "拜拜")
        val rows = emojis.mapIndexed { i, e -> "$e  ${labels[i]}" }

        val listAdapter = ArrayAdapter(this, R.layout.item_emoji, R.id.tvEmoji, rows)
        AlertDialog.Builder(this)
            .setTitle("😊 选个表情")
            .setAdapter(listAdapter) { _, i ->
                send { chat?.sendEmoji(peerId, emojis[i]) }
            }
            .show()
    }

    private fun onIncoming(m: Msg) {
        runOnUiThread {
            if (adapter.add(m)) {
                rv.scrollToPosition(adapter.itemCount - 1)
                refreshPeerBar()
            }
        }
    }

    /** 点气泡：语音 → 下载后播放；照片 → 看大图。 */
    private fun onBubbleClick(m: Msg) {
        when {
            m is VoiceMeta -> playVoice(m)
            m is ChatMsg && m.kind == "photo" -> showBigPhoto(m.body)
        }
    }

    private fun playVoice(m: VoiceMeta) {
        toast("⬇️ 正在收语音…")
        chat?.fetchMedia(m.uri) { f ->
            if (f == null) { toast("😢 这条语音没收到"); return@fetchMedia }
            runCatching {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    setOnCompletionListener { runCatching { it.release() }; player = null }
                    prepare()
                    start()
                }
            }.onFailure { toast("😢 播放不了这条语音") }
        }
    }

    private fun showBigPhoto(url: String) {
        photoBitmap(url) { bmp ->
            if (bmp == null) { toast("😢 照片没收到"); return@photoBitmap }
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            AlertDialog.Builder(this)
                .setTitle("🖼️ 照片")
                .setView(iv)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    /** 异步加载照片位图：先查缓存，再走本地文件 / HTTP 下载，最后在 IO 线程解码。 */
    private fun photoBitmap(url: String, onReady: (Bitmap?) -> Unit) {
        photoCache[url]?.let { onReady(it); return }
        val c = chat
        if (c == null) { onReady(null); return }
        c.fetchMedia(url) { f ->
            if (f == null) { onReady(null); return@fetchMedia }
            lifecycleScope.launch(Dispatchers.IO) {
                val b = runCatching { decodeSampled(f, 1280) }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (b != null) photoCache[url] = b
                    onReady(b)
                }
            }
        }
    }

    private fun decodeSampled(f: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (maxOf(w, h) / sample > maxSide * 2) sample *= 2
        return BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AppBus.activeFeature == Feature.CHAT) AppBus.activeFeature = null
        runCatching { player?.release() }
        player = null
        // 位图交给 GC（这里不 recycle：正在显示的 dialog/列表可能还在用）
        photoCache.clear()
        if (!closedByRemote) {
            runCatching { AppBus.postFeatureExit(Feature.CHAT) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** 极简聊天气泡适配器：自己靠右、对端靠左，照片显示缩略图，按消息 id 去重。 */
private class ChatAdapter(
    private val onClick: (Msg) -> Unit,
    private val loadPhoto: (String, (Bitmap?) -> Unit) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<Msg>()
    private val ids = HashSet<String>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tvBubble)
        val photo: ImageView = v.findViewById(R.id.ivPhoto)
    }

    /** @return true = 真的新增了一条（重复消息被丢弃）。 */
    fun add(m: Msg): Boolean {
        if (!ids.add(m.id)) return false
        items.add(m)
        notifyItemInserted(items.size - 1)
        return true
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_chat, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        val mine = m.from == AppBus.myId
        val who = if (mine) "" else
            (AppBus.peers.firstOrNull { it.id == m.from }?.name?.let { "$it：" } ?: "小伙伴：")

        val photoUrl = (m as? ChatMsg)?.takeIf { it.kind == "photo" }?.body
        if (photoUrl != null) {
            h.photo.visibility = View.VISIBLE
            h.photo.tag = photoUrl
            h.photo.setImageBitmap(null)
            loadPhoto(photoUrl) { bmp ->
                if (h.photo.tag == photoUrl && bmp != null) h.photo.setImageBitmap(bmp)
            }
        } else {
            h.photo.visibility = View.GONE
            h.photo.tag = null
        }

        h.text.text = who + when (m) {
            is ChatMsg -> when (m.kind) {
                "emoji" -> "😊 ${m.body}"
                "photo" -> "📷 照片（点一下看大图）"
                else -> m.body
            }
            is VoiceMeta -> "🎤 语音 ${(m.durMs / 1000).coerceAtLeast(1)} 秒（点一下听）"
            else -> m.toString()
        }
        h.text.setBackgroundResource(if (mine) R.drawable.bubble_mine else R.drawable.bubble_peer)
        h.photo.setBackgroundResource(if (mine) R.drawable.bubble_mine else R.drawable.bubble_peer)

        val lp = h.text.layoutParams as LinearLayout.LayoutParams
        lp.gravity = if (mine) Gravity.END else Gravity.START
        h.text.layoutParams = lp
        val plp = h.photo.layoutParams as LinearLayout.LayoutParams
        plp.gravity = if (mine) Gravity.END else Gravity.START
        h.photo.layoutParams = plp

        h.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount() = items.size
}
