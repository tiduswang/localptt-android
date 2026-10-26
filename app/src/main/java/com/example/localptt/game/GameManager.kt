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
 * 防作弊设计（commit-reveal，核心）：
 *   1. 双方各自随机出招 value + 随机 nonce，计算 HASH(nonce + ":" + value)；
 *   2. 第一轮只广播「承诺」[GameCommit]（仅 hash，不泄露 value）；
 *   3. 双方承诺都到齐后，各自才广播「揭晓」[GameMove]（value + nonce）；
 *   4. 双方都拿到对方的揭晓后，**各自独立裁定**（规则确定性，无需互发结果）；
 *      任一方 hash 校验失败即判本局无效。
 *
 * ★ 历史 Bug 修复（只显示出招、永远不出结果）：
 *  1. **主机发不出消息**：过去 `server != null -> Unit`，主机的出招/揭晓一条都发不出去，
 *     对端永远收不到主机承诺 → 双方都不揭晓 → 没有结果。现在统一走 AppBus.post。
 *  2. **过早 settled**：maybeJudge() 一进来就把 peer 标记为已裁定，此时对方还没揭晓，
 *     等对方揭晓到达时又被 settled 挡住，结果永不产生。
 *     现在只有「我方出招 + 对方揭晓」都齐备时才裁定。
 *  3. **连玩多局失效**：收到新承诺时必须清空该对端的 revealed/settled/peerReveal，
 *     否则第二局不会再揭晓。
 */
class GameManager(private val myId: String) {

    /**
     * 本局结果（双方都揭晓并校验通过后触发）。
     *
     * ★ 必须是 SharedFlow，不能是 Channel：Channel 是单消费者的，
     *   若同时存在两个收集者（功能同步可能重复打开游戏页），结果只会落到其中一个，
     *   表现为「这局有人看到结果、有人永远停在等结果」。
     *   replay 保证后打开的页面也能补到最后一次结果。
     */
    private val _results = MutableSharedFlow<GameResult>(replay = 4, extraBufferCapacity = 8)
    val results: SharedFlow<GameResult> = _results

    /** 收到的对端邀请。 */
    private val _invites = MutableSharedFlow<GameInvite>(replay = 4, extraBufferCapacity = 8)
    val invites: SharedFlow<GameInvite> = _invites

    /** 我方出招（peerId -> 承诺内容）。 */
    private data class Pending(val game: String, val value: Int, val nonce: String, val commitment: String)

    private val mine = mutableMapOf<String, Pending>()
    private val peerCommit = mutableMapOf<String, String>()            // peerId -> 对端承诺 hash
    private val peerReveal = mutableMapOf<String, Pair<Int, String>>() // peerId -> (value, nonce)
    private val gameOf = mutableMapOf<String, String>()                // peerId -> 游戏类型
    private val revealed = mutableSetOf<String>()                      // 我方已揭晓的 peerId
    private val settled = mutableSetOf<String>()                       // 已裁定的 peerId

    /** 用锁保护多协程（TCP 读线程 / UI）并发访问上面的可变状态。 */
    private val lock = Any()

    fun results(): Flow<GameResult> = results
    fun invites(): Flow<GameInvite> = invites

    /** 邀请对端进行某游戏。 */
    fun invite(peerId: String, game: String) {
        synchronized(lock) { gameOf[peerId] = game }
        send(GameInvite(myId, peerId, uid(), game))
    }

    /** 我方出招：只发承诺，等待对端承诺到齐后自动揭晓。 */
    fun play(peerId: String, game: String, value: Int) {
        val nonce = randNonce()
        val commitment = hash("$nonce:$value")
        synchronized(lock) {
            mine[peerId] = Pending(game, value, nonce, commitment)
            // 开新一局：清掉上一局的揭晓/裁定痕迹，但保留对端已到的承诺（可能比我们早出招）
            peerReveal.remove(peerId)
            revealed.remove(peerId)
            settled.remove(peerId)
            gameOf[peerId] = game
        }
        send(GameCommit(myId, peerId, uid(), game, commitment))
        maybeReveal(peerId)
    }

    /** 清空与某对端的对局状态（重新开局用）。 */
    fun reset(peerId: String) {
        synchronized(lock) {
            mine.remove(peerId); peerCommit.remove(peerId); peerReveal.remove(peerId)
            revealed.remove(peerId); settled.remove(peerId)
        }
    }

    /** 信令分发入口：由 MainActivity.dispatch 调用。 */
    fun onMessage(m: Msg) {
        if (m.from == myId) return
        when (m) {
            is GameInvite -> {
                synchronized(lock) { gameOf[m.from] = m.game }
                _invites.tryEmit(m)
            }
            is GameCommit -> {
                synchronized(lock) {
                    peerCommit[m.from] = m.commitment
                    gameOf.getOrPut(m.from) { m.game }
                    // 对端开了新一局：清掉上一局的揭晓/裁定痕迹，允许再次揭晓
                    peerReveal.remove(m.from)
                    revealed.remove(m.from)
                    settled.remove(m.from)
                }
                maybeReveal(m.from)
            }
            is GameMove -> {
                val expected = synchronized(lock) { peerCommit[m.from] }
                if (expected != null && hash("${m.nonce}:${m.value}") != expected) {
                    // 对端改招：校验失败，作废本局
                    Log.w("LocalPTT", "对端出招校验失败，本局作废")
                    synchronized(lock) { settled.add(m.from) }
                    return
                }
                synchronized(lock) { peerReveal[m.from] = m.value to m.nonce }
                maybeJudge(m.from)
            }
            else -> Unit
        }
    }

    /** 双方承诺齐备 -> 揭晓我方出招。 */
    private fun maybeReveal(peerId: String) {
        val me = synchronized(lock) {
            val m = mine[peerId] ?: return
            if (peerCommit[peerId] == null) return
            if (!revealed.add(peerId)) return
            m
        }
        send(GameMove(myId, peerId, uid(), me.game, me.value, me.nonce))
        maybeJudge(peerId)
    }

    /** 双方都已揭晓 -> 校验 + 各自独立裁定（规则确定性，两端结果必然一致）。 */
    private fun maybeJudge(peerId: String) {
        val (me, peerValue, game) = synchronized(lock) {
            val m = mine[peerId] ?: return
            val pr = peerReveal[peerId] ?: return          // 对方还没揭晓 → 还不能裁定
            if (!settled.add(peerId)) return               // 本局已裁定过
            Triple(m, pr.first, gameOf[peerId] ?: m.game)
        }
        val winner = when (game) {
            "rps" -> rpsWinner(me.value, peerValue, myId, peerId)
            "dice" -> when {
                me.value > peerValue -> myId
                peerValue > me.value -> peerId
                else -> "draw"
            }
            else -> "draw"
        }
        val r = GameResult(myId, peerId, uid(), game, winner, me.value, peerValue)
        Log.i("LocalPTT", "本局裁定 game=$game 我=${me.value} 对端=$peerValue winner=$winner")
        _results.tryEmit(r)
        // 结果由双方各自计算（规则确定性），不再互发，避免互相覆盖或顺序问题
    }

    /** 猜拳裁定：0=石头 1=剪刀 2=布；a 胜 b 当且仅当 (a+1)%3 == b。 */
    private fun rpsWinner(a: Int, b: Int, me: String, peer: String): String {
        if (a == b) return "draw"
        return if ((a + 1) % 3 == b) me else peer
    }

    private fun send(m: Msg) = AppBus.post(m)

    private fun randNonce(): String = UUID.randomUUID().toString()
    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    private fun uid(): String = UUID.randomUUID().toString().take(8)
}
