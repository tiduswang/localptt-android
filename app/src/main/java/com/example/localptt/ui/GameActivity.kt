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
import com.example.localptt.data.GameResult
import com.example.localptt.game.GameManager
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

    /** 对端是否也在这个功能页里出现过。 */
    private var peerWasHere = false

    /** 掷骰子是否正在播放「滚动」动画（避免连点重复出招）。 */
    private var rolling = false

    /** 本页是否已经出过招 —— 没出招之前绝不显示结果（否则会看到上一页遗留的旧结果）。 */
    private var everPlayed = false

    /** 我最近一次出招的局号，用于过滤重放的旧结果。 */
    private var lastPlayedRound = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        AppBus.activeFeature = Feature.GAME

        tvStatus = findViewById(R.id.tvGameStatus)
        tvResult = findViewById(R.id.tvGameResult)

        // 顶部实时信号（我 / 对方）
        findViewById<TextView>(R.id.tvLink).bindLinkQuality(this)

        findViewById<View>(R.id.btnGameBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnInviteRps).setOnClickListener { startGame("rps") }
        findViewById<View>(R.id.btnInviteDice).setOnClickListener { startGame("dice") }

        // 猜拳出招
        findViewById<View>(R.id.btnRock).setOnClickListener { play(0) }
        findViewById<View>(R.id.btnScissors).setOnClickListener { play(1) }
        findViewById<View>(R.id.btnPaper).setOnClickListener { play(2) }

        // 掷骰子：先播「滚动」动画，动画结束才锁定点数出招
        findViewById<View>(R.id.btnDiceRoll).setOnClickListener { rollDice() }

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

        // 对端退出 → 我也退出（exit 事件 / 对端进了别的功能 / 对端曾在这里、现在回了首页）
        lifecycleScope.launch {
            AppBus.featureEvents.collect { s ->
                if (s.from == AppBus.myId) return@collect
                if (s.feature == Feature.GAME && s.action != Feature.EXIT) peerWasHere = true
                val explicitExit = s.action == Feature.EXIT && s.feature == Feature.GAME
                val peerEnteredOther = s.action == Feature.ENTER && s.feature != Feature.GAME
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
        AppBus.activeFeature = Feature.GAME
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
        everPlayed = false
        lastPlayedRound = 0
        game?.invite(peer.id, type)
        tvStatus.text = "第 1 局 · 已邀请 ${peer.name} 玩 ${gameName(type)}，快出招！"
        tvResult.text = if (type == "dice") "🎲 点「掷骰子」开扔～" else "🤔 出招后等结果…"
        refreshPeerHint()
    }

    /** 掷骰子：先在界面上滚 0.6 秒，再锁定点数、把承诺发给对端（局号由 GameManager 管理）。 */
    private fun rollDice() {
        if (rolling) return
        if (peerId == null) {
            toast("先选一个游戏，叫上小伙伴吧")
            return
        }
        gameType = "dice"
        val peer = peerId ?: return
        if (game?.roundOf(peer) == 0) return
        rolling = true
        lifecycleScope.launch {
            repeat(9) {
                val face = (1..6).random()
                tvResult.text = "${diceFace(face)}\n🎲 咕噜咕噜…"
                delay(65)
            }
            rolling = false
            play((1..6).random())
        }
    }

    private fun play(value: Int) {
        val peer = peerId
        if (peer == null) {
            toast("先选一个游戏，叫上小伙伴吧")
            return
        }
        val no = game?.play(peer, gameType, value) ?: return
        if (no == 0) {
            toast("本局已经出过招啦，等小伙伴一起开～")
            return
        }
        everPlayed = true
        lastPlayedRound = no
        tvStatus.text = "第 $no 局 · 你已出招，等小伙伴一起开～"
        tvResult.text = if (gameType == "dice") {
            "${diceFace(value)}\n🎲 你掷出了 $value 点\n🤔 等小伙伴掷骰子…"
        } else {
            "${moveEmoji(gameType, value)} 你出了 ${moveName(gameType, value)}\n🤔 等小伙伴出招…"
        }
    }

    private fun showResult(r: GameResult) {
        // 没出过招 / 上一局重放出来的旧结果，一律不显示 —— 杜绝「还没扔就比出胜负」
        if (!everPlayed || r.round == 0 || r.round < lastPlayedRound) return
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
            val iconA = if (r.game == "dice") diceFace(r.a) else moveEmoji(r.game, r.a)
            val iconB = if (r.game == "dice") diceFace(r.b) else moveEmoji(r.game, r.b)
            tvResult.text =
                "$iconA 你：${moveName(r.game, r.a)}\n" +
                        "$iconB $peerName：${moveName(r.game, r.b)}\n" +
                        "$w$cmp"
            tvStatus.text = "第 ${r.round} 局结束 · 再出一次招就是第 ${r.round + 1} 局 😊"
        }
    }

    /** 骰子点数 → 六面骰字符（不支持时退回 🎲）。 */
    private fun diceFace(v: Int): String = when (v) {
        1 -> "⚀"; 2 -> "⚁"; 3 -> "⚂"; 4 -> "⚃"; 5 -> "⚄"; 6 -> "⚅"
        else -> "🎲"
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
            runCatching { AppBus.postFeatureExit(Feature.GAME) }
        }
    }

    private fun gameName(g: String) = if (g == "dice") "🎲 掷骰子" else "✊ 猜拳"
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
