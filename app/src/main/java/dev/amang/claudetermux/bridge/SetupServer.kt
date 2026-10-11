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
    private val onDone: () -> Unit,
) {
    private val secret = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val baseUrl = "http://127.0.0.1:${server.localPort}/$secret"

    /** What the user pastes into Termux. */
    val command: String = "curl -s $baseUrl/setup | bash"

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
        while (!reader.readLine().isNullOrEmpty()) Unit // headers are irrelevant
        val (method, path) = requestLine.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val out = socket.getOutputStream()

        fun respond(status: String, type: String, body: ByteArray) {
            out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }

        when {
            method == "GET" && path == "/$secret/setup" -> {
                val script = context.assets.open("setup-termux.sh").use { it.readBytes() }.decodeToString()
                    .replace("\r\n", "\n")
                    .replace("__TOKEN__", token)
                    .replace("__BRIDGE_PORT__", bridgePort.toString())
                    .replace("__SETUP_URL__", baseUrl)
                respond("200 OK", "text/x-shellscript", script.toByteArray())
            }
            method == "GET" && path == "/$secret/bridge.tgz" ->
                respond("200 OK", "application/gzip", context.assets.open("bridge.tgz").use { it.readBytes() })
            method == "POST" && path == "/$secret/done" -> {
                respond("200 OK", "text/plain", "ok".toByteArray())
                onDone()
            }
            else -> respond("404 Not Found", "text/plain", "not found".toByteArray())
        }
    }
}
