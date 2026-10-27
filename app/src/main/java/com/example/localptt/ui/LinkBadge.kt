package com.example.localptt.ui

import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.localptt.net.LinkQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 把「我 / 对方」的实时信号刷到一个 TextView 上（语音 / 视频 / 微聊 / 游戏四个页面共用）。
 * 1.5 秒刷一次，页面销毁时协程随 lifecycle 自动取消。
 */
fun TextView.bindLinkQuality(owner: LifecycleOwner) {
    owner.lifecycleScope.launch {
        while (true) {
            text = LinkQuality.summary()
            delay(1500)
        }
    }
}
