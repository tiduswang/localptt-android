package com.example.localptt.net

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 网络层统一协程护栏。
 *
 * 背景：协程里未捕获的异常默认会传播到线程的 UncaughtExceptionHandler，
 * 在没有安装处理器时直接杀进程 —— 表现为「对端一断开 App 就闪退」。
 * 这里统一拦截并只记录日志，让网络错误退化成功能不可用，而不是崩溃。
 */
val netGuard = CoroutineExceptionHandler { _, e ->
    Log.w("LocalPTT", "网络协程异常已拦截：${e.javaClass.simpleName}: ${e.message}")
}

fun netScope() = CoroutineScope(Dispatchers.IO + SupervisorJob() + netGuard)

/**
 * 安全创建 UDP socket：优先绑定 [port]，被占用则依次递增（最多 31 次）。
 * 全部失败时返回 null，由调用方空转，而不是抛异常崩溃。
 */
fun openUdpSocket(port: Int, onPort: (Int) -> Unit = {}): DatagramSocket? {
    for (p in port..port + 30) {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(p))
            }
        } catch (_: Exception) {
            null
        }
        if (s != null) {
            onPort(p)
            return s
        }
    }
    Log.w("LocalPTT", "UDP 端口 $port 及其后续 30 个端口均被占用")
    return null
}

/** 安全创建 TCP 服务端 socket，端口占用时递增。 */
fun openServerSocket(port: Int, onPort: (Int) -> Unit = {}): ServerSocket? {
    for (p in port..port + 30) {
        val s = try {
            ServerSocket(p)
        } catch (_: Exception) {
            null
        }
        if (s != null) {
            onPort(p)
            return s
        }
    }
    Log.w("LocalPTT", "TCP 端口 $port 及其后续 30 个端口均被占用")
    return null
}
