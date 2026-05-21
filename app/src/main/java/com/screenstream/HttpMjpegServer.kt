package com.hkhop.screenstream

import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class HttpMjpegServer(private val port: Int) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clientStreams = CopyOnWriteArrayList<OutputStream>()
    private val serverExecutor = Executors.newCachedThreadPool()

    fun start() {
        isRunning = true
        serverExecutor.execute {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    serverExecutor.execute { handleClient(socket) }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val outputStream = socket.getOutputStream()
            
            // Standard HTTP MJPEG Header configuration
            val header = ("HTTP/1.0 200 OK\r\n" +
                    "Server: AndroidScreenStream\r\n" +
                    "Connection: close\r\n" +
                    "Max-Age: 0\r\n" +
                    "Expires: 0\r\n" +
                    "Cache-Control: no-cache, private, no-store, must-revalidate, max-age=0, post-check=0, pre-check=0\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=--boundary\r\n\r\n").toByteArray()
            
            outputStream.write(header)
            outputStream.flush()

            // Register stream for global frame broadcast updates
            clientStreams.add(outputStream)

            // Keep socket alive until client explicitly drops connection
            val inputStream = socket.getInputStream()
            val dummyBuffer = ByteArray(1024)
            while (isRunning && inputStream.read(dummyBuffer) != -1) {
                // Keep-alive loop reading client requests/headers
            }
        } catch (e: IOException) {
            // Client closed stream connection
        } finally {
            try {
                clientStreams.remove(socket.getOutputStream())
                socket.close()
            } catch (_: Exception) {}
        }
    }

    fun broadcastFrame(jpegBytes: ByteArray) {
        if (clientStreams.isEmpty()) return

        // Multi-part formatting block
        val frameHeader = ("--boundary\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpegBytes.size}\r\n\r\n").toByteArray()

        for (stream in clientStreams) {
            try {
                stream.write(frameHeader)
                stream.write(jpegBytes)
                stream.write("\r\n".toByteArray())
                stream.flush()
            } catch (e: IOException) {
                // Remove broken pipe connection safely
                clientStreams.remove(stream)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        clientStreams.clear()
        serverExecutor.shutdownNow()
    }
}
