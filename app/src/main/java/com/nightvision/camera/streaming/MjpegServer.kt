package com.nightvision.camera.streaming

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Lightweight MJPEG HTTP Server
 * Streams JPEG frames over HTTP multipart/x-mixed-replace
 * Viewable in any browser: http://DEVICE_IP:8080/stream
 * Also viewable in the built-in ViewerActivity
 */
class MjpegServer(private val port: Int = 8080) {

    private val TAG = "MjpegServer"
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var running = false

    private var frameCount = 0L
    private var startTime = 0L

    data class ClientConnection(
        val socket: Socket,
        val outputStream: OutputStream,
        var isReady: Boolean = false
    )

    fun start() {
        if (running) return
        running = true
        startTime = System.currentTimeMillis()

        executor.execute {
            try {
                serverSocket = ServerSocket(port).apply {
                    soTimeout = 0 // No timeout on accept
                    reuseAddress = true
                }
                Log.i(TAG, "MJPEG server started on port $port")

                while (running && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        Log.d(TAG, "Client connected: ${clientSocket.inetAddress.hostAddress}")
                        executor.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            // Read HTTP request
            val sb = StringBuilder()
            val buffer = ByteArray(1024)
            var n = 0
            try {
                n = inputStream.read(buffer)
            } catch (e: Exception) { /* timeout ok */ }

            if (n > 0) {
                sb.append(String(buffer, 0, n))
            }

            val request = sb.toString()
            Log.v(TAG, "Request: ${request.lines().firstOrNull()}")

            when {
                request.contains("GET /stream") -> {
                    // Send MJPEG stream headers
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n")
                        append("Cache-Control: no-cache, no-store\r\n")
                        append("Pragma: no-cache\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    outputStream.write(response.toByteArray(Charsets.US_ASCII))
                    outputStream.flush()
                    socket.soTimeout = 0

                    val conn = ClientConnection(socket, outputStream, isReady = true)
                    clients.add(conn)
                    Log.i(TAG, "Streaming client added. Total: ${clients.size}")

                    // Keep alive until disconnected
                    while (running && !socket.isClosed) {
                        Thread.sleep(500)
                    }
                    clients.remove(conn)
                }

                request.contains("GET /snapshot") -> {
                    // Serve last frame as JPEG snapshot
                    lastFrame?.let { jpeg ->
                        val response = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: image/jpeg\r\n")
                            append("Content-Length: ${jpeg.size}\r\n")
                            append("Cache-Control: no-cache\r\n")
                            append("Access-Control-Allow-Origin: *\r\n")
                            append("\r\n")
                        }
                        outputStream.write(response.toByteArray(Charsets.US_ASCII))
                        outputStream.write(jpeg)
                        outputStream.flush()
                    } ?: run {
                        outputStream.write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray())
                    }
                    socket.close()
                }

                request.contains("GET /info") -> {
                    val fps = if (System.currentTimeMillis() - startTime > 0)
                        (frameCount * 1000 / (System.currentTimeMillis() - startTime)).toString()
                    else "0"
                    val json = """{"clients":${clients.size},"frames":$frameCount,"fps":$fps}"""
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${json.length}\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        append("\r\n")
                        append(json)
                    }
                    outputStream.write(response.toByteArray(Charsets.US_ASCII))
                    socket.close()
                }

                else -> {
                    // Serve HTML viewer page
                    val html = buildViewerHtml()
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Content-Length: ${html.toByteArray().size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                        append(html)
                    }
                    outputStream.write(response.toByteArray(Charsets.UTF_8))
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private var lastFrame: ByteArray? = null

    /**
     * Send a JPEG frame to all connected streaming clients
     */
    fun sendFrame(jpegBytes: ByteArray) {
        lastFrame = jpegBytes
        frameCount++

        if (clients.isEmpty()) return

        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val footer = "\r\n".toByteArray(Charsets.US_ASCII)

        val toRemove = mutableListOf<ClientConnection>()
        for (conn in clients) {
            if (!conn.isReady) continue
            try {
                synchronized(conn.outputStream) {
                    conn.outputStream.write(header)
                    conn.outputStream.write(jpegBytes)
                    conn.outputStream.write(footer)
                    conn.outputStream.flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Client write failed, removing: ${e.message}")
                toRemove.add(conn)
                try { conn.socket.close() } catch (_: Exception) {}
            }
        }
        clients.removeAll(toRemove)
    }

    fun getClientCount(): Int = clients.size

    fun stop() {
        running = false
        clients.forEach { try { it.socket.close() } catch (_: Exception) {} }
        clients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        Log.i(TAG, "MJPEG server stopped. Total frames sent: $frameCount")
    }

    private fun buildViewerHtml(): String = """
        <!DOCTYPE html>
        <html lang="ar" dir="rtl">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
            <title>🌙 Night Vision Camera</title>
            <style>
                * { margin:0; padding:0; box-sizing:border-box; }
                body { background:#0a0a0a; color:#00e676; font-family:monospace; display:flex; flex-direction:column; align-items:center; min-height:100vh; }
                .header { padding:16px; text-align:center; border-bottom:1px solid #1a1a1a; width:100%; }
                h1 { font-size:1.2em; color:#00e676; }
                .subtitle { font-size:0.8em; color:#4caf50; margin-top:4px; }
                .stream-container { flex:1; display:flex; align-items:center; justify-content:center; padding:16px; width:100%; }
                #stream { max-width:100%; max-height:80vh; border:2px solid #00e676; border-radius:8px; display:block; }
                .status { padding:8px 16px; font-size:0.75em; color:#4caf50; text-align:center; }
                .dot { display:inline-block; width:8px; height:8px; background:#00e676; border-radius:50%; margin-right:6px; animation:blink 1s infinite; }
                @keyframes blink { 0%,100%{opacity:1} 50%{opacity:0.2} }
                .controls { padding:16px; display:flex; gap:12px; justify-content:center; }
                button { background:#1a1a1a; color:#00e676; border:1px solid #00e676; padding:10px 20px; border-radius:8px; font-size:0.9em; cursor:pointer; }
                button:active { background:#00e676; color:#000; }
            </style>
        </head>
        <body>
            <div class="header">
                <h1>🌙 Night Vision Camera</h1>
                <div class="subtitle">البث المباشر - Live Stream</div>
            </div>
            <div class="stream-container">
                <img id="stream" src="/stream" alt="Live Stream" onerror="onError()">
            </div>
            <div class="status">
                <span class="dot"></span>
                بث مباشر | Live &nbsp;·&nbsp; <span id="info">جارٍ الاتصال...</span>
            </div>
            <div class="controls">
                <button onclick="refreshStream()">🔄 تحديث</button>
                <button onclick="toggleFullscreen()">⛶ ملء الشاشة</button>
                <button onclick="takeSnapshot()">📷 لقطة</button>
            </div>
            <script>
                function refreshStream() {
                    const img = document.getElementById('stream');
                    img.src = '/stream?' + Date.now();
                }
                function onError() {
                    document.getElementById('info').textContent = 'انقطع الاتصال - محاولة إعادة الاتصال...';
                    setTimeout(refreshStream, 3000);
                }
                function toggleFullscreen() {
                    const img = document.getElementById('stream');
                    if (document.fullscreenElement) { document.exitFullscreen(); }
                    else { img.requestFullscreen(); }
                }
                function takeSnapshot() {
                    const a = document.createElement('a');
                    a.href = '/snapshot';
                    a.download = 'snapshot.jpg';
                    a.click();
                }
                setInterval(() => {
                    fetch('/info').then(r => r.json()).then(d => {
                        document.getElementById('info').textContent = 
                            `${"$"}{d.clients} متصل · ${"$"}{d.fps} إطار/ث`;
                    }).catch(() => {});
                }, 2000);
            </script>
        </body>
        </html>
    """.trimIndent()
}
