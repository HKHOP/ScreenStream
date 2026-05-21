package com.screenstream

import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpMjpegServer(
    private val port: Int,
    private val onClientCountChanged: (Int) -> Unit
) {

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    private val clients = CopyOnWriteArrayList<OutputStream>()
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning.getAndSet(true)) return

        executor.execute {
            try {
                serverSocket = ServerSocket(port)

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    executor.execute { handleClient(socket) }
                }

            } catch (_: IOException) {
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var output: OutputStream? = null

        try {
            output = socket.getOutputStream()

            val header =
                "HTTP/1.0 200 OK\r\n" +
                "Server: ScreenStream\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=boundary\r\n\r\n"

            output.write(header.toByteArray())
            output.flush()

            clients.add(output)
            onClientCountChanged(clients.size)

            val buffer = ByteArray(1024)

            // keep connection alive until closed
            val input = socket.getInputStream()

            while (isRunning.get()) {
                val read = input.read(buffer)
                if (read == -1) break
            }

        } catch (_: IOException) {

        } finally {
            try {
                output?.let {
                    clients.remove(it)
                }
                onClientCountChanged(clients.size)
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun broadcastFrame(jpeg: ByteArray) {
        if (!isRunning.get() || clients.isEmpty()) return

        val header =
            "--boundary\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${jpeg.size}\r\n\r\n"

        val headerBytes = header.toByteArray()

        for (client in clients) {
            try {
                client.write(headerBytes)
                client.write(jpeg)
                client.write("\r\n".toByteArray())
                client.flush()
            } catch (_: IOException) {
                clients.remove(client)
                onClientCountChanged(clients.size)
            }
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        try {
            serverSocket?.close()
        } catch (_: IOException) {}

        for (c in clients) {
            try {
                c.close()
            } catch (_: Exception) {}
        }

        clients.clear()
        onClientCountChanged(0)

        executor.shutdownNow()
    }
}
