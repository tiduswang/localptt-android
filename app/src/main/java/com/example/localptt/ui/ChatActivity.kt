package com.example.localptt.ui

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.localptt.data.FeatureSync
import com.example.localptt.data.Msg
import com.example.localptt.data.VoiceMeta
import com.example.localptt.net.uid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 微聊页：文字 / 表情 / 语音消息 / 照片。
 *
 * ★ 历史 Bug 修复（发的消息对方收不到）：
 *  1. ChatManager 现在由 App.onCreate 创建一次（进程内单例），不再随建网/重连重建；
 *     过去功能页订阅的是旧实例的消息流，重连后就成了「能发出去、但永远收不到」。
 *  2. 消息流订阅改为**可自愈**：即使打开页面时管理器还没就绪，也会轮询到可用再订阅。
 *  3. 发送时若尚未建网会明确提示，而不是静默丢弃。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var tvPeer: TextView
    private val adapter = ChatAdapter { m -> onBubbleClick(m) }

    private var player: MediaPlayer? = null

    /**
     * 消息目标：统一**广播给房间里所有人**。
     *
     * ★ 过去按缓存里的 peerId 定向发送：一旦名单过期（重连过、对端掉线又回来），
     *   主机在 peers 表里查不到这个 id，就会把消息**静默丢弃** ——
     *   表现正是「我这边显示发出去了，对方却一条都看不到」。
     *   广播既贴合「房间里一起聊」的场景，也彻底消除了这个静默丢弃。
     *   接收侧靠 ChatMsg.from 区分是谁说的，多人房间同样正确。
     */
    private val peerId: String get() = "all"

    private val chat: ChatManager? get() = AppBus.chat

    /** 是否由对端同步关闭。 */
    private var closedByRemote = false

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) send { chat?.sendPhoto(peerId, uri) }
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
            if (t.isNotEmpty()) {
                if (send { chat?.sendText(peerId, t) }) etInput.setText("")
            }
        }

        findViewById<View>(R.id.btnEmoji).setOnClickListener { sendEmoji() }
        findViewById<View>(R.id.btnPhoto).setOnClickListener { pickPhoto.launch("image/*") }

        val btnVoice = findViewById<View>(R.id.btnVoiceMsg)
        val tvVoiceIcon = findViewById<TextView>(R.id.tvVoiceIcon)
        val tvVoiceLabel = findViewById<TextView>(R.id.tvVoiceLabel)
        btnVoice.setOnTouchListener { _, ev ->
            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    chat?.startVoice()
                    tvVoiceIcon.text = "🔴"
                    tvVoiceLabel.text = "松手发送"
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    send { chat?.stopVoiceAndSend(peerId) }
                    tvVoiceIcon.text = "🎤"
                    tvVoiceLabel.text = "按住说话"
                    toast("📤 语音发出去啦")
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
        return runCatching { block() }.isSuccess
    }

    private fun refreshPeerBar() {
        val peer = AppBus.others.firstOrNull()
        val name = peer?.name ?: "所有人（广播）"
        tvPeer.text = "💬 正在和 $name 聊天"
        findViewById<android.widget.ImageView>(R.id.ivChatPeerAvatar)
            ?.setImageResource(Avatars.drawableOf(peer?.avatar ?: "bear"))
    }

    /** 表情面板：大号表情 + 文字说明，小朋友一看就懂。 */
    private fun sendEmoji() {
        val emojis = arrayOf("👍", "❤️", "😂", "😮", "😢", "🎉", "⭐", "👋")
        val labels = arrayOf("真棒", "喜欢", "太好笑", "哇！", "难过", "好耶", "厉害", "拜拜")
        val rows = emojis.mapIndexed { i, e -> "$e  ${labels[i]}" }

        val adapter = ArrayAdapter(this, R.layout.item_emoji, R.id.tvEmoji, rows)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("😊 选个表情")
            .setAdapter(adapter) { _, i ->
                send { chat?.sendEmoji(peerId, emojis[i]) }
            }
            .show()
    }

    private fun onIncoming(m: Msg) {
        runOnUiThread {
            adapter.add(m)
            rv.scrollToPosition(adapter.itemCount - 1)
            refreshPeerBar()
        }
    }

    /** 点语音气泡 → 下载并播放（对方的语音存在对方手机上，需 HTTP 拉取）。 */
    private fun onBubbleClick(m: Msg) {
        if (m !is VoiceMeta) return
        if (m.from == AppBus.myId) return
        toast("⬇️ 正在收语音…")
        chat?.downloadVoice(m.uri) { f ->
            if (f == null) { toast("😢 这条语音没收到"); return@downloadVoice }
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

    override fun onDestroy() {
        super.onDestroy()
        if (AppBus.activeFeature == Feature.CHAT) AppBus.activeFeature = null
        runCatching { player?.release() }
        player = null
        if (!closedByRemote) {
            runCatching {
                AppBus.post(FeatureSync(AppBus.myId, "all", uid(), Feature.CHAT, Feature.EXIT))
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** 极简聊天气泡适配器：自己靠右、对端靠左（对端会带上名字，孩子看得懂是谁说的）。 */
private class ChatAdapter(
    private val onClick: (Msg) -> Unit
) : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<Msg>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tvBubble)
    }

    fun add(m: Msg) {
        items.add(m)
        notifyItemInserted(items.size - 1)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
        VH(LayoutInflater.from(p.context).inflate(R.layout.item_chat, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = items[pos]
        val mine = m.from == AppBus.myId
        val who = if (mine) "" else
            (AppBus.peers.firstOrNull { it.id == m.from }?.name?.let { "$it：" } ?: "小伙伴：")
        h.text.text = who + when (m) {
            is ChatMsg -> when (m.kind) {
                "emoji" -> "😊 ${m.body}"
                "photo" -> "📷 [照片]"
                else -> m.body
            }
            is VoiceMeta -> "🎤 语音 ${(m.durMs / 1000).coerceAtLeast(1)} 秒（点一下听）"
            else -> m.toString()
        }
        h.text.setBackgroundResource(if (mine) R.drawable.bubble_mine else R.drawable.bubble_peer)
        (h.text.layoutParams as FrameLayout.LayoutParams).gravity =
            if (mine) Gravity.END else Gravity.START
        h.itemView.setOnClickListener { onClick(m) }
    }

    override fun getItemCount() = items.size
}
