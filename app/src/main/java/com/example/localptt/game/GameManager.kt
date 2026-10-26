package com.example.localptt.game

import android.util.Log
import com.example.localptt.AppBus
import com.example.localptt.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.security.MessageDigest
import java.util.UUID

/**
 * 联机小游戏：猜拳(rps) / 掷骰子比大小(dice)。
 *
 * 防作弊设计（commit-reveal）：
 *   1. 双方各自随机出招 value + 随机 nonce，计算 HASH(nonce + ":" + value)；
 *   2. 第一轮只广播「承诺」[GameCommit]（仅 hash，不泄露 value）；
 *   3. 双方承诺都到齐后，各自才广播「揭晓」[GameMove]（value + nonce）；
 *   4. 双方都拿到对方的揭晓后，**各自独立裁定**（规则确定性，无需互发结果）。
 *
 * ★ 「一局一局」的局号设计（v1.3.1 修复「还没扔就比出胜负」「没有一局的概念」）：
 *   每个对端维护一个 [Round]（局号 + 本局状态）。GameCommit / GameMove 都带 `round`：
 *    - 局号小于当前局的旧消息一律丢弃，避免**上一局的出招参与本局裁定**；
 *    - 我方「出过招还没裁定」时拒绝再次出招（返回 0），不能重复掷；
 *    - 裁定完立刻 `局号 + 1` 并清空本局状态，下一次出招自然属于新一局；
 *    - 只有「我方本局出招 + 对端本局揭晓」都在才裁定，所以绝不会出现「还没扔就出结果」。
 *
 * ★ 历史 Bug 修复（只显示出招、永远不出结果）：
 *  1. **主机发不出消息**：过去 `server != null -> Unit`，主机一条信令都发不出去。
 *  2. **过早 settled**：maybeJudge() 一进来就把 peer 标记为已裁定 → 对方揭晓后不再裁定。
 *     现在只有「我方出招 + 对方揭晓」都齐备时才裁定。
 */
class GameManager(private val myId: String) {

    /**
     * 本局结果（双方都揭晓并校验通过后触发）。
     * 必须用 SharedFlow：Channel 是单消费者的，功能同步可能同时存在两个游戏页。
     */
    private val _results = MutableSharedFlow<GameResult>(replay = 4, extraBufferCapacity = 8)
    val results: SharedFlow<GameResult> = _results

    /** 收到的对端邀请。 */
    private val _invites = MutableSharedFlow<GameInvite>(replay = 4, extraBufferCapacity = 8)
    val invites: SharedFlow<GameInvite> = _invites

    /** 我方出招（peerId -> 承诺内容）。 */
    private data class Pending(val game: String, val value: Int, val nonce: String, val commitment: String)

    /** 与某个对端「当前这一局」的全部状态；局号 +1 时整体清空，上一局的数据不会漏进下一局。 */
    private class Round {
        var no = 1                       // 局号（从 1 起，UI 显示「第 N 局」）
        var game: String? = null
        var mine: Pending? = null        // 我方本局出的招
        var peerCommit: String? = null    // 对端本局承诺 hash（只证明「他出了」，不足以裁定）
        var peerValue: Int? = null        // 对端本局揭晓的点数 / 手势
        var peerNonce: String? = null
        var revealed = false              // 我方是否已揭晓
        var settled = false               // 本局是否已裁定

        fun nextRound(no: Int) {
            this.no = no
            mine = null; peerCommit = null; peerValue = null; peerNonce = null
            revealed = false; settled = false
        }
    }

    private data class Judge(val mine: Int, val peer: Int, val game: String, val no: Int)

    private val rounds = mutableMapOf<String, Round>()   // peerId -> 当前局
    private val lock = Any()                              // TCP 读线程 / UI 线程并发保护

    fun results(): Flow<GameResult> = results
    fun invites(): Flow<GameInvite> = invites

    /** 当前与 [peerId] 进行到第几局（从 1 起）。 */
    fun roundOf(peerId: String): Int = synchronized(lock) { round(peerId).no }

    /** 邀请对端进行某游戏。 */
    fun invite(peerId: String, game: String) {
        synchronized(lock) { round(peerId).game = game }
        send(GameInvite(myId, peerId, uid(), game))
    }

    /**
     * 我方在本局出招：只发承诺，等对端承诺到齐后自动揭晓。
     * @return 本局局号；**0 表示本局已经出过招**（重复点击被忽略，等结果即可）。
     */
    fun play(peerId: String, game: String, value: Int): Int {
        val nonce = randNonce()
        val commitment = hash("$nonce:$value")
        val no = synchronized(lock) {
            val r = round(peerId)
            if (r.mine != null && !r.settled) return 0     // 本局已掷过，不能再掷
            r.game = game
            r.mine = Pending(game, value, nonce, commitment)
            r.revealed = false
            r.settled = false
            r.no
        }
        send(GameCommit(myId, peerId, uid(), game, commitment, no))
        maybeReveal(peerId)
        return no
    }

    /** 清空与某对端的对局状态（重新选游戏时用）。 */
    fun reset(peerId: String) {
        synchronized(lock) { rounds.remove(peerId) }
    }

    /** 信令分发入口：由 MainActivity.dispatch 调用。 */
    fun onMessage(m: Msg) {
        if (m.from == myId) return
        when (m) {
            is GameInvite -> {
                synchronized(lock) { round(m.from).game = m.game }
                _invites.tryEmit(m)
            }
            is GameCommit -> {
                synchronized(lock) {
                    val r = round(m.from)
                    advance(r, m.round)
                    if (m.round != 0 && m.round < r.no) return   // 上一局的旧承诺，丢弃
                    r.game = m.game
                    if (r.peerCommit != m.commitment) {
                        // 对端在本局重新出了招：清掉本局揭晓/裁定痕迹
                        r.peerCommit = m.commitment
                        r.peerValue = null; r.peerNonce = null
                        r.revealed = false; r.settled = false
                    }
                }
                maybeReveal(m.from)
            }
            is GameMove -> {
                var bad = false
                synchronized(lock) {
                    val r = round(m.from)
                    advance(r, m.round)
                    if (m.round != 0 && m.round < r.no) return           // 上一局的旧揭晓，丢弃
                    val expected = r.peerCommit
                    if (expected != null && hash("${m.nonce}:${m.value}") == expected) {
                        r.peerValue = m.value
                        r.peerNonce = m.nonce
                    } else {
                        bad = true
                        r.settled = true                                 // 对端改招，本局作废
                    }
                }
                if (bad) {
                    Log.w("LocalPTT", "对端出招校验失败，本局作废")
                    return
                }
                maybeJudge(m.from)
            }
            else -> Unit
        }
    }

    /** 局号前进（对端比我先裁定完时用）：整局状态作废，绝不让上一局的数据参与下一局。 */
    private fun advance(r: Round, no: Int) {
        if (no > r.no) r.nextRound(no)
    }

    /** 双方承诺齐备 -> 揭晓我方出招。 */
    private fun maybeReveal(peerId: String) {
        val (me, no) = synchronized(lock) {
            val r = round(peerId)
            val m = r.mine ?: return
            if (r.peerCommit == null) return
            if (r.revealed) return
            r.revealed = true
            m to r.no
        }
        send(GameMove(myId, peerId, uid(), me.game, me.value, me.nonce, no))
        maybeJudge(peerId)
    }

    /** 双方本局都已揭晓 -> 校验 + 各自独立裁定（规则确定性，两端结果必然一致）。 */
    private fun maybeJudge(peerId: String) {
        val judge = synchronized(lock) {
            val r = round(peerId)
            val m = r.mine ?: return                       // 我还没掷，绝不裁定
            val pv = r.peerValue ?: return                 // 对端还没揭晓，不能裁定
            if (r.settled) return
            r.settled = true
            Judge(m.value, pv, r.game ?: m.game, r.no)
        }
        val winner = when (judge.game) {
            "rps" -> rpsWinner(judge.mine, judge.peer, myId, peerId)
            "dice" -> when {
                judge.mine > judge.peer -> myId
                judge.peer > judge.mine -> peerId
                else -> "draw"
            }
            else -> "draw"
        }
        val no = judge.no
        synchronized(lock) { round(peerId).nextRound(no + 1) }   // 本局结束，进入下一局
        val r = GameResult(myId, peerId, uid(), judge.game, winner, judge.mine, judge.peer, no)
        Log.i("LocalPTT", "第 $no 局裁定 game=${judge.game} 我=${judge.mine} 对端=${judge.peer} winner=$winner")
        _results.tryEmit(r)
    }

    /** 猜拳裁定：0=石头 1=剪刀 2=布；a 胜 b 当且仅当 (a+1)%3 == b。 */
    private fun rpsWinner(a: Int, b: Int, me: String, peer: String): String {
        if (a == b) return "draw"
        return if ((a + 1) % 3 == b) me else peer
    }

    private fun round(peerId: String): Round = rounds.getOrPut(peerId) { Round() }

    private fun send(m: Msg) = AppBus.post(m)

    private fun randNonce(): String = UUID.randomUUID().toString()
    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    private fun uid(): String = UUID.randomUUID().toString().take(8)
}
