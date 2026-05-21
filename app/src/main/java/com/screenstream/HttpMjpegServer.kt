package com.screenstream

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A simple HTTP MJPEG streaming server.
 *
 * Compatible with VLC, browsers (Chrome/Firefox), and any media player
 * that supports multipart/x-mixed-replace streams.
 *
 * Open URL in VLC:  http://<device-ip>:<port>
 * Open URL in browser: http://<device-ip>:<port>
 */
class HttpMjpegServer(private val port: Int) {

    companion object {
        private const val TAG = "HttpMjpegServer"
        private const val BOUNDARY = "mjpegstream"
    }

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientOutput>()
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    // Listener for connection count updates
    var onClientCountChanged: ((Int) -> Unit)? = null

    data class ClientOutput(val stream: OutputStream, val socket: Socket)

    fun start() {
        if (running.getAndSet(true)) return
        executor.execute {
            try {
                serverSocket = ServerSocket(port).also { it.reuseAddress = true }
                Log.i(TAG, "MJPEG server started on port $port")
                while (running.get()) {
                    try {
                        val socket = serverSocket!!.accept()
                        executor.execute { handleClient(socket) }
                    } catch (e: SocketException) {
                        if (running.get()) Log.e(TAG, "Socket accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server failed to start: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val input = socket.getInputStream()

            // Drain the HTTP request headers
            val buf = ByteArray(4096)
            val read = input.read(buf)
            if (read <= 0) { socket.close(); return }

            val request = String(buf, 0, read)
            Log.d(TAG, "Client connected from ${socket.inetAddress.hostAddress}")

            // We don't care what path was requested — always stream
            socket.soTimeout = 0

            val out = socket.getOutputStream()
            val response = buildString {
                append("HTTP/1.0 200 OK\r\n")
                append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                append("Pragma: no-cache\r\n")
                append("Connection: close\r\n")
                append("Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n")
                append("\r\n")
            }
            out.write(response.toByteArray(Charsets.UTF_8))
            out.flush()

            val clientOut = ClientOutput(out, socket)
            clients.add(clientOut)
            onClientCountChanged?.invoke(clients.size)

            // Block this thread until client disconnects
            try {
                while (running.get() && !socket.isClosed) {
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) {}

            clients.remove(clientOut)
            onClientCountChanged?.invoke(clients.size)
            socket.close()
            Log.d(TAG, "Client disconnected. Active: ${clients.size}")

        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
            socket.runCatching { close() }
        }
    }

    /**
     * Push a JPEG frame to all connected clients.
     * Call this from your capture loop.
     */
    fun sendFrame(jpegData: ByteArray) {
        if (!running.get() || clients.isEmpty()) return

        val header = buildString {
            append("--$BOUNDARY\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${jpegData.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)

        val footer = "\r\n".toByteArray(Charsets.UTF_8)

        val dead = mutableListOf<ClientOutput>()
        for (client in clients) {
            try {
                client.stream.write(header)
                client.stream.write(jpegData)
                client.stream.write(footer)
                client.stream.flush()
            } catch (e: Exception) {
                dead.add(client)
            }
        }
        if (dead.isNotEmpty()) {
            dead.forEach { it.socket.runCatching { close() } }
            clients.removeAll(dead.toSet())
            onClientCountChanged?.invoke(clients.size)
        }
    }

    fun getClientCount(): Int = clients.size

    fun stop() {
        running.set(false)
        clients.forEach { it.socket.runCatching { close() } }
        clients.clear()
        serverSocket?.runCatching { close() }
        serverSocket = null
        Log.i(TAG, "MJPEG server stopped")
    }
}
