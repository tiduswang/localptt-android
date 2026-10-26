package com.example.localptt.data

import com.example.localptt.R

/**
 * 卡通头像库：10 个内置头像，全部为自绘 vector（不依赖 emoji 字体，老机型也不会变豆腐块）。
 * key 会随 Hello / PeerList 在网络上传输，因此对端也能显示你的头像。
 */
object Avatars {
    val keys = listOf(
        "bear", "rabbit", "cat", "dog", "fox",
        "panda", "tiger", "frog", "koala", "unicorn"
    )

    val labels = listOf(
        "小熊", "小兔", "小猫", "小狗", "小狐狸",
        "熊猫", "老虎", "青蛙", "考拉", "独角兽"
    )

    fun drawableOf(key: String): Int = when (key) {
        "rabbit" -> R.drawable.av_rabbit
        "cat" -> R.drawable.av_cat
        "dog" -> R.drawable.av_dog
        "fox" -> R.drawable.av_fox
        "panda" -> R.drawable.av_panda
        "tiger" -> R.drawable.av_tiger
        "frog" -> R.drawable.av_frog
        "koala" -> R.drawable.av_koala
        "unicorn" -> R.drawable.av_unicorn
        else -> R.drawable.av_bear
    }

    fun labelOf(key: String): String {
        val i = keys.indexOf(key)
        return if (i >= 0) labels[i] else labels[0]
    }

    /** 未设置头像时按设备 id 稳定地分配一个，避免每次都变。 */
    fun defaultFor(id: String): String {
        val h = id.fold(0) { acc, c -> acc * 31 + c.code }
        return keys[Math.floorMod(h, keys.size)]
    }
}
