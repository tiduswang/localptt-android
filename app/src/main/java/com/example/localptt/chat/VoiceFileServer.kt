package com.example.localptt.chat

import com.example.localptt.net.netScope
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 主机侧静态文件服务：把 cacheDir/voice 下的语音消息以 HTTP 暴露给组内客户端。
 * 仅服务于本地 2.4GHz 对讲网络，不对外网开放，因此实现刻意保持极简（无鉴权、无目录穿越防护外的多余逻辑）。
 *
 * 用法：主机在 becomeHost() 时 start(dir)；客户端通过 ChatManager.voiceUrl() 拼出的 URL 下载。
 */
class VoiceFileServer(
    private val dir: File,
    private val port: Int = PORT
) {
    companion object {
        const val PORT = 8899
        private const val TAG = "VoiceFileServer"
    }

    private var server: ServerSocket? = null
    private val scope = netScope()
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch {
            runCatching {
                server = ServerSocket(port)
                while (running) {
                    val sock = runCatching { server?.accept() }.getOrNull() ?: break
                    launch { handle(sock) }
                }
            }
        }
    }

    private suspend fun handle(sock: Socket) = withContext(Dispatchers.IO) {
        runCatching {
            sock.use { s ->
                val input = s.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return@use
                // 形如：GET /voice_123.m4a HTTP/1.1
                val name = requestLine.split(" ").getOrNull(1)?.trimStart('/')?.takeIf { it.isNotEmpty() }
                // 消费掉剩余请求头，避免客户端写阻塞
                while (input.readLine()?.isNotEmpty() == true) { /* no-op */ }

                val file = name?.let { File(dir, it) }
                val ok = file != null && file.isFile &&
                        file.canonicalPath.startsWith(dir.canonicalPath)   // 防目录穿越
                if (!ok || file == null) {
                    respond(s.getOutputStream(), 404, "text/plain", "not found".toByteArray())
                } else {
                    respond(s.getOutputStream(), 200, "audio/mp4", file.readBytes())
                }
            }
        }
    }

    private fun respond(out: OutputStream, code: Int, mime: String, body: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        scope.cancel()
    }
}
