package com.example.localptt.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.localptt.AppBus
import com.example.localptt.R
import com.example.localptt.data.Avatars
import com.example.localptt.data.Feature
import com.example.localptt.data.FeatureSync
import com.example.localptt.data.GameResult
import com.example.localptt.game.GameManager
import com.example.localptt.net.uid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 联机小游戏页：猜拳(rps) / 掷骰子比大小(dice)。
 * 出招经 [GameManager] 走 commit-reveal 防作弊流程，结果由 results 流返回。
 *
 * ★ 历史 Bug 修复（只显示「谁出了什么」，永远不出胜负）：
 *  根因在网络层而非 UI —— 主机侧 GameManager 过去根本不发送出招/揭晓消息，
 *  于是双方都等不到对方的承诺，裁定永远不触发。现在双方各自独立裁定并展示结果。
 */
class GameActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView

    private val game: GameManager? get() = AppBus.game

    /** 当前对手与游戏类型（由邀请或本地选择决定）。 */
    private var peerId: String? = null
    private var gameType: String = "rps"

    /** 是否由对端同步关闭。 */
    private var closedByRemote = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        AppBus.activeFeature = Feature.GAME

        tvStatus = findViewById(R.id.tvGameStatus)
        tvResult = findViewById(R.id.tvGameResult)

        findViewById<View>(R.id.btnGameBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnInviteRps).setOnClickListener { startGame("rps") }
        findViewById<View>(R.id.btnInviteDice).setOnClickListener { startGame("dice") }

        // 猜拳出招
        findViewById<View>(R.id.btnRock).setOnClickListener { play(0) }
        findViewById<View>(R.id.btnScissors).setOnClickListener { play(1) }
        findViewById<View>(R.id.btnPaper).setOnClickListener { play(2) }

        // 掷骰子：随机 1~6
        findViewById<View>(R.id.btnDiceRoll).setOnClickListener { play((1..6).random()) }

        // 默认对手：房间里第一个小伙伴（不用先点「邀请」也能出招）
        if (peerId == null) peerId = AppBus.others.firstOrNull()?.id
        refreshPeerHint()

        lifecycleScope.launch {
            while (true) {
                val g = AppBus.game
                if (g != null) {
                    runCatching {
                        g.results().collect { showResult(it) }
                    }.onFailure { android.util.Log.w("LocalPTT", "游戏结果流异常：${it.message}") }
                }
                delay(500)
            }
        }
        lifecycleScope.launch {
            while (true) {
                val g = AppBus.game
                if (g != null) {
                    runCatching {
                        g.invites().collect { inv ->
                            peerId = inv.from
                            gameType = inv.game
                            runOnUiThread {
                                tvStatus.text = "${peerNameOf(inv.from)} 叫你玩 ${gameName(inv.game)}，快出招！"
                                tvResult.text = "🤔 等结果…"
                                refreshPeerHint()
                            }
                        }
                    }.onFailure { android.util.Log.w("LocalPTT", "游戏邀请流异常：${it.message}") }
                }
                delay(500)
            }
        }

        // 对端退出 → 我也退出
        lifecycleScope.launch {
            AppBus.featureEvents.collect { s ->
                if (s.feature == Feature.GAME && s.action == Feature.EXIT) {
                    closedByRemote = true
                    finish()
                }
            }
        }
    }

    private fun startGame(type: String) {
        val peer = AppBus.others.firstOrNull()
        if (peer == null) {
            toast("还没看到小伙伴，先回首页连上他吧")
            return
        }
        peerId = peer.id
        gameType = type
        game?.reset(peer.id)
        game?.invite(peer.id, type)
        tvStatus.text = "已邀请 ${peer.name} 玩 ${gameName(type)}，快出招！"
        tvResult.text = "🤔 等结果…"
        refreshPeerHint()
    }

    private fun play(value: Int) {
        val peer = peerId
        if (peer == null) {
            toast("先选一个游戏，叫上小伙伴吧")
            return
        }
        game?.play(peer, gameType, value)
        tvStatus.text = "🔒 出好啦！等小伙伴一起开～"
        tvResult.text = "${moveEmoji(gameType, value)} 你出了 ${moveName(gameType, value)}\n🤔 等小伙伴出招…"
    }

    private fun showResult(r: GameResult) {
        runOnUiThread {
            val peerName = AppBus.peers.firstOrNull { it.id == r.to }?.name ?: "小伙伴"
            val w = when (r.winner) {
                AppBus.myId -> "🎉 你赢啦！"
                "draw" -> "🤝 平局，再来一局！"
                else -> "😢 $peerName 赢啦，再来！"
            }
            val cmp = if (r.game == "dice") {
                when {
                    r.a > r.b -> "（你多 ${r.a - r.b} 点）"
                    r.b > r.a -> "（少 ${r.b - r.a} 点）"
                    else -> "（一样大）"
                }
            } else {
                when {
                    r.a == r.b -> "（一样）"
                    r.winner == AppBus.myId -> "（你的赢他的）"
                    else -> "（他的赢你的）"
                }
            }
            tvResult.text =
                "${moveEmoji(r.game, r.a)} 你：${moveName(r.game, r.a)}\n" +
                        "${moveEmoji(r.game, r.b)} $peerName：${moveName(r.game, r.b)}\n" +
                        "$w$cmp"
            tvStatus.text = "本局结束啦，可以再出一次招 😊"
        }
    }

    /** 出招值 → 可爱图标 */
    private fun moveEmoji(game: String, v: Int) = when {
        game == "dice" -> "🎲"
        v == 0 -> "✊"
        v == 1 -> "✌️"
        else -> "✋"
    }

    /** 出招值 → 小朋友读得懂的说法 */
    private fun moveName(game: String, v: Int) = when {
        game == "dice" -> "$v 点"
        v == 0 -> "石头"
        v == 1 -> "剪刀"
        else -> "布"
    }

    private fun refreshPeerHint() {
        val peer = AppBus.peers.firstOrNull { it.id == peerId } ?: AppBus.others.firstOrNull()
        findViewById<TextView>(R.id.tvGamePeer)?.text = "🧒 对手：${peer?.name ?: "还没选"}"
        findViewById<android.widget.ImageView>(R.id.ivGamePeerAvatar)
            ?.setImageResource(Avatars.drawableOf(peer?.avatar ?: "bear"))
    }

    /** 对端 id → 昵称，拿不到就显示 id。 */
    private fun peerNameOf(id: String): String =
        AppBus.peers.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id

    override fun onDestroy() {
        super.onDestroy()
        if (AppBus.activeFeature == Feature.GAME) AppBus.activeFeature = null
        if (!closedByRemote) {
            runCatching {
                AppBus.post(FeatureSync(AppBus.myId, "all", uid(), Feature.GAME, Feature.EXIT))
            }
        }
    }

    private fun gameName(g: String) = if (g == "dice") "🎲 掷骰子" else "✊ 猜拳"
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
