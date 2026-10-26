package com.example.localptt

import android.content.Context
import com.example.localptt.data.Avatars

/**
 * 我的名片：昵称 + 卡通头像，存在 SharedPreferences（与 MainActivity 共用 "localptt"）。
 * 进程启动时由 App 载入到 AppBus，任何页面都能直接用 AppBus.myName / AppBus.myAvatar。
 */
object Profile {
    private const val PREF = "localptt"
    private const val K_NAME = "nickName"
    private const val K_AVATAR = "avatar"

    /** 载入本地名片；没设置过就按设备 id 分配默认头像与默认昵称。 */
    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val name = p.getString(K_NAME, null)?.trim().orEmpty()
        val avatar = p.getString(K_AVATAR, null)?.trim().orEmpty()
        AppBus.myName = if (name.isEmpty()) defaultName() else name
        AppBus.myAvatar = if (avatar.isEmpty()) Avatars.defaultFor(AppBus.myId) else avatar
    }

    /** 保存并同步到 AppBus（已建网时下次 Hello/PeerList 会自动带上新头像）。 */
    fun save(ctx: Context, name: String, avatar: String) {
        val n = name.trim().ifEmpty { defaultName() }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(K_NAME, n).putString(K_AVATAR, avatar).apply()
        AppBus.myName = n
        AppBus.myAvatar = avatar
    }

    /** 默认昵称：小朋友 + 3 位随机数，可爱且基本不重名。 */
    fun defaultName(): String = "小朋友${(100..999).random()}"

    fun hasCustomized(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).contains(K_NAME)
}
