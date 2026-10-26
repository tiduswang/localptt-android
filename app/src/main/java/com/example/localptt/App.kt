package com.example.localptt

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.localptt.chat.ChatManager
import com.example.localptt.game.GameManager
import com.example.localptt.ui.MainActivity
import kotlin.system.exitProcess

/**
 * 应用入口。两件事：
 *  1. 在任何 Activity 之前初始化稳定的 myId / myName —— 解决「进程被回收后重建子页面时
 *     AppBus.myId 尚未初始化导致 UninitializedPropertyAccessException 闪退」。
 *  2. 兜底未捕获异常：记录日志后重启到首页，避免直接闪退回桌面且无提示。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppBus.myId = "dev_${deviceId()}"
        // 优先用用户自己设置的昵称与头像；没设置过则自动生成「小朋友XXX + 默认头像」
        Profile.load(this)
        if (AppBus.myName.isBlank()) AppBus.myName = Build.MODEL ?: "Android"
        // 微聊与游戏管理器只创建一次（进程内单例）：功能页订阅的 Flow 才能一直有效，
        // 掉线重连替换 client/server 时也不会「消息收不到」。
        runCatching { AppBus.chat = ChatManager(this) }
        runCatching { AppBus.game = GameManager(AppBus.myId) }
        installCrashGuard()
    }

    private fun deviceId(): String {
        val aid = runCatching {
            android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull()
        return (aid ?: "unknown").takeLast(6)
    }

    /**
     * 兜底：协程/线程里未捕获的异常默认会杀进程（表现为「闪退」）。
     * 这里统一记录，并尝试回到首页，让用户至少能继续操作。
     * 10 秒内二次崩溃则不再重启，避免陷入崩溃-重启死循环。
     */
    private fun installCrashGuard() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("LocalPTT", "未捕获异常 thread=${thread.name}", throwable)
            val now = System.currentTimeMillis()
            val canRestart = now - lastCrashAt > 10_000L
            lastCrashAt = now
            if (canRestart) {
                runCatching {
                    val i = Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra("crash", true)
                    }
                    startActivity(i)
                }
            } else {
                Log.e("LocalPTT", "短时间内重复崩溃，停止自动重启")
            }
            default?.uncaughtException(thread, throwable) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(2)
            }
        }
    }

    @Volatile private var lastCrashAt = 0L
}
