package dev.amang.claudetermux.bridge

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Loopback-only HTTP server that hands the setup script and the bridge bundle to Termux during
 * onboarding. Every path sits behind a random secret because the script carries the pairing token
 * and other apps on the phone can reach 127.0.0.1 too.
 */
class SetupServer(
    private val context: Context,
    private val token: String,
    private val bridgePort: Int,
    private val onProgress: (step: String, percent: Int?) -> Unit,
    private val onLog: (tail: String) -> Unit,
    private val onFailed: (log: String) -> Unit,
    private val onDone: () -> Unit,
) {
    private val secret = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val baseUrl = "http://127.0.0.1:${server.localPort}/$secret"

    /** What Termux runs to set itself up; also shown for pasting by hand. */
    val command: String = "curl -s $baseUrl/setup | bash"

    /** Like [command], but only replaces the bridge when Claude Code is already installed. */
    val updateCommand: String = "curl -s $baseUrl/update | bash"

    init {
        thread(name = "setup-server", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { runCatching { socket.use(::handle) } }
            }
        }
    }

    fun stop() = runCatching { server.close() }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val requestLine = reader.readLine() ?: return
        var length = 0
        while (true) {
            val header = reader.readLine()
            if (header.isNullOrEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val (method, path) = requestLine.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val out = socket.getOutputStream()

        fun respond(status: String, type: String, body: ByteArray) {
            out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }

        // The script sends plain ASCII, so characters and bytes line up.
        fun body(): String {
            val chars = CharArray(length.coerceIn(0, 16_384))
            var read = 0
            while (read < chars.size) {
                val n = reader.read(chars, read, chars.size - read)
                if (n < 0) break
                read += n
            }
            return String(chars, 0, read).trim()
        }

        when {
            method == "GET" && (path == "/$secret/setup" || path == "/$secret/update") -> {
                val script = context.assets.open("setup-termux.sh").use { it.readBytes() }.decodeToString()
                    .replace("\r\n", "\n")
                    .replace("__TOKEN__", token)
                    .replace("__BRIDGE_PORT__", bridgePort.toString())
                    .replace("__SETUP_URL__", baseUrl)
                    .replace("__MODE__", if (path.endsWith("/update")) "bridge" else "full")
                respond("200 OK", "text/x-shellscript", script.toByteArray())
                onProgress("fetched", null)
            }
            method == "GET" && path == "/$secret/bridge.tgz" ->
                respond("200 OK", "application/gzip", context.assets.open("bridge.tgz").use { it.readBytes() })
            method == "POST" && path.startsWith("/$secret/progress/") -> {
                respond("200 OK", "text/plain", "ok".toByteArray())
                // "<step>" or "<step>/<percent>"
                val parts = path.removePrefix("/$secret/progress/").split('/')
                onProgress(parts[0], parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100))
            }
            method == "POST" && path == "/$secret/log" -> {
                val tail = body()
                respond("200 OK", "text/plain", "ok".toByteArray())
                onLog(tail)
            }
            method == "POST" && path == "/$secret/failed" -> {
                val log = body()
                respond("200 OK", "text/plain", "ok".toByteArray())
                onFailed(log)
            }
            method == "POST" && path == "/$secret/done" -> {
                respond("200 OK", "text/plain", "ok".toByteArray())
                onDone()
            }
            else -> respond("404 Not Found", "text/plain", "not found".toByteArray())
        }
    }
}
