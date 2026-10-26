package com.example.localptt.data

import kotlinx.serialization.*
import kotlinx.serialization.json.Json

/**
 * 统一信令/消息协议（控制面走 TCP JSON；媒体面走 UDP/RTP，不在此协议内）。
 * 约定：to=="all" 表示广播（主机转发给所有在线节点）。
 */
object Codec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(m: Msg) = json.encodeToString(m)
    fun decode(s: String): Msg = json.decodeFromString(s)
}

@Serializable
sealed interface Msg {
    val from: String          // 发送方节点 ID（= 设备指纹）
    val to: String            // 接收方 ID 或 "all"
    val id: String            // 消息唯一 ID，用于去重/应答
}

@Serializable @SerialName("hello")
data class Hello(
    override val from: String, override val to: String, override val id: String,
    val name: String, val role: String,   // "host" | "client"
    val avatar: String = "bear",          // 卡通头像 key，见 Avatars
    /**
     * 本机 UDP 语音实际监听的端口。
     * 5004 被占用时 UdpBus 会自动顺延；不带端口会让对端一直发到 5004 而收不到声音。
     */
    val voicePort: Int = 5004
) : Msg

/**
 * 主机 → 客户端的「加入确认」。
 * 客户端必须收到本消息（或包含自己的 peerlist）才算真正互联成功，
 * 避免「客户端已连上、主机却毫不知情」的状态不一致（历史 Bug 根因之一）。
 */
@Serializable @SerialName("welcome")
data class Welcome(
    override val from: String, override val to: String, override val id: String,
    val hostName: String, val roomName: String, val selfIp: String
) : Msg

/** 心跳：主机每 2.5s 下发，客户端回 Pong；超时未回则主机剔除该成员。 */
@Serializable @SerialName("ping")
data class Ping(
    override val from: String, override val to: String, override val id: String,
    val ts: Long = 0L
) : Msg

@Serializable @SerialName("pong")
data class Pong(
    override val from: String, override val to: String, override val id: String,
    val ts: Long = 0L
) : Msg

/** 主机定期下发的在线名单 */
@Serializable @SerialName("peerlist")
data class PeerList(
    override val from: String, override val to: String, override val id: String,
    val peers: List<Peer>
) : Msg

@Serializable
data class Peer(
    val id: String,
    val name: String,
    val ip: String,
    val role: String,          // "host" | "client"
    val avatar: String = "bear",
    /** 对端 UDP 语音端口（见 [Hello.voicePort]）。 */
    val voicePort: Int = 5004
)

/** 微聊：kind ∈ {text, emoji, voice, photo} */
@Serializable @SerialName("chat")
data class ChatMsg(
    override val from: String, override val to: String, override val id: String,
    val kind: String, val body: String, val ts: Long
) : Msg

/** 语音消息元信息（音频二进制走 HTTP/文件，这里只传索引/路径） */
@Serializable @SerialName("voicemeta")
data class VoiceMeta(
    override val from: String, override val to: String, override val id: String,
    val durMs: Long, val uri: String
) : Msg

/** 视频通话信令：action ∈ {invite, accept, reject, bye} */
@Serializable @SerialName("video")
data class VideoCall(
    override val from: String, override val to: String, override val id: String,
    val action: String
) : Msg

/** 小游戏邀请：game ∈ {rps 猜拳, dice 掷骰子比大小} */
@Serializable @SerialName("game_invite")
data class GameInvite(
    override val from: String, override val to: String, override val id: String,
    val game: String
) : Msg

/**
 * 游戏出招。value 含义：
 *  - rps: 0=石头 1=剪刀 2=布
 *  - dice: 1~6
 * nonce 用于服务端校验「双方同时亮招、防作弊」（见 GameManager）。
 */
@Serializable @SerialName("game_move")
data class GameMove(
    override val from: String, override val to: String, override val id: String,
    val game: String, val value: Int, val nonce: String
) : Msg

/**
 * 小游戏「承诺」：只公开 HASH(nonce + ":" + value)，不泄露 value。
 * 双方都提交承诺后才允许 reveal（发 GameMove），从而无法在看到对方出招后改招。
 */
@Serializable @SerialName("game_commit")
data class GameCommit(
    override val from: String, override val to: String, override val id: String,
    val game: String, val commitment: String
) : Msg

@Serializable @SerialName("game_result")
data class GameResult(
    override val from: String, override val to: String, override val id: String,
    val game: String, val winner: String, val a: Int, val b: Int
) : Msg

@Serializable @SerialName("bye")
data class Bye(
    override val from: String, override val to: String, override val id: String
) : Msg

/**
 * 功能同步：一台设备进入/退出某个功能页，房间内其他设备同步进入/退出。
 *
 * feature ∈ {voice, video, chat, game}
 * action  ∈ {enter, exit}
 */
@Serializable @SerialName("feature")
data class FeatureSync(
    override val from: String, override val to: String, override val id: String,
    val feature: String,
    val action: String
) : Msg

/** 功能页常量，避免各处硬编码字符串写错。 */
object Feature {
    const val VOICE = "voice"
    const val VIDEO = "video"
    const val CHAT = "chat"
    const val GAME = "game"
    const val ENTER = "enter"
    const val EXIT = "exit"
}
