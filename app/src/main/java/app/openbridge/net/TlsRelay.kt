package app.openbridge.net

import app.openbridge.core.Diag
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Ktor 3 CIO не умеет HTTPS, поэтому TLS-терминация вынесена в отдельный слой:
 * приложение слушает внешний TLS-порт, расшифровывает поток и передаёт его
 * на внутренний loopback-порт, где работает обычный HTTP-сервер Ktor.
 *
 * @param listenPort   внешний порт с TLS (его видят другие устройства)
 * @param upstreamPort внутренний loopback-порт HTTP-сервера
 */
class TlsRelay(
    private val sslContext: SSLContext,
    private val listenPort: Int,
    private val upstreamPort: Int,
) {

    init {
        require(listenPort in 1..65535) { "listenPort out of range: $listenPort" }
        require(upstreamPort in 1..65535) { "upstreamPort out of range: $upstreamPort" }
    }

    private var serverSocket: SSLServerSocket? = null
    private var acceptor: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val socket = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(listenPort), 50)
        serverSocket = socket
        running = true

        acceptor = Thread({ acceptLoop(socket) }, "openbridge-tls").apply {
            isDaemon = true
            start()
        }

        Diag.log("relay", "TLS $listenPort -> 127.0.0.1:$upstreamPort")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptor?.interrupt() }
        acceptor = null
    }

    private fun acceptLoop(socket: SSLServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (error: Exception) {
                if (running) Diag.log("relay", "accept failed: $error")
                break
            }
            val worker = Thread({ handle(client) }, "openbridge-tls-conn")
            worker.isDaemon = true
            worker.start()
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            val ssl = client as? SSLSocket
            if (ssl != null) {
                ssl.useClientMode = false
                ssl.startHandshake()
            }

            val target = Socket()
            target.connect(InetSocketAddress("127.0.0.1", upstreamPort), 5_000)
            target.tcpNoDelay = true
            upstream = target

            val back = Thread({ pump(client, target) }, "openbridge-tls-up")
            back.isDaemon = true
            back.start()

            pump(target, client)
        } catch (error: Exception) {
            Diag.log("relay", "connection failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val input: InputStream = from.getInputStream()
            val output: OutputStream = to.getOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
            runCatching { to.shutdownOutput() }
        } catch (_: Exception) {
            // соединение закрылось — обычная ситуация
        }
    }
}