package com.nightvision.camera.streaming

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.UUID

class MjpegServer(private val port: Int = 8080) {

    private val TAG = "MjpegServer"
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private val executor = Executors.newCachedThreadPool()
    private val CRLF = "\r\n"

    @Volatile var running = false
    @Volatile var accessCode: String = generateCode()
    @Volatile var streamEnabled: Boolean = true
    private var frameCount = 0L
    private var startTime = 0L

    data class ClientConnection(
        val socket: Socket,
        val outputStream: OutputStream,
        val ip: String,
        val id: String = UUID.randomUUID().toString().take(8),
        var isReady: Boolean = false,
        val connectedAt: Long = System.currentTimeMillis()
    )

    fun generateCode(): String = (1000..9999).random().toString()

    fun resetCode(): String {
        accessCode = generateCode()
        disconnectAll()
        return accessCode
    }

    fun disconnectAll() {
        clients.forEach { try { it.socket.close() } catch (_: Exception) {} }
        clients.clear()
    }

    fun getConnectedClients(): List<Map<String, String>> = clients.map {
        mapOf("id" to it.id, "ip" to it.ip, "since" to "${(System.currentTimeMillis() - it.connectedAt)/1000}s")
    }

    fun start() {
        if (running) return
        running = true
        startTime = System.currentTimeMillis()
        executor.execute {
            try {
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                Log.i(TAG, "Server started port=$port")
                while (running && !serverSocket!!.isClosed) {
                    try {
                        val s = serverSocket!!.accept()
                        executor.execute { handleClient(s) }
                    } catch (e: Exception) { if (running) Log.e(TAG, e.message ?: "") }
                }
            } catch (e: Exception) { if (running) Log.e(TAG, e.message ?: "") }
        }
    }

    private fun buildResponse(code: Int, contentType: String, body: ByteArray): ByteArray {
        val header = "HTTP/1.1 $code OK" + CRLF +
            "Content-Type: $contentType" + CRLF +
            "Content-Length: ${body.size}" + CRLF +
            "Access-Control-Allow-Origin: *" + CRLF + CRLF
        return header.toByteArray() + body
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val ip = socket.inetAddress.hostAddress ?: "unknown"
            val buf = ByteArray(4096)
            var n = 0
            try { n = socket.getInputStream().read(buf) } catch (_: Exception) {}
            if (n <= 0) { socket.close(); return }
            val req = String(buf, 0, n)
            val path = req.lines().firstOrNull()?.split(" ")?.getOrNull(1) ?: "/"
            val urlCode = path.substringAfter("code=", "").substringBefore("&")
            val out = socket.getOutputStream()

            when {
                path.startsWith("/stream") -> {
                    if (!streamEnabled) {
                        val e = "<html><body>503 البث متوقف</body></html>".toByteArray()
                        out.write(buildResponse(503, "text/html", e))
                        socket.close(); return
                    }
                    if (urlCode != accessCode) {
                        val e = "<html><body>401 كود خاطئ</body></html>".toByteArray()
                        out.write(buildResponse(401, "text/html", e))
                        socket.close(); return
                    }
                    val streamHeader = "HTTP/1.1 200 OK" + CRLF +
                        "Content-Type: multipart/x-mixed-replace; boundary=frame" + CRLF +
                        "Cache-Control: no-cache" + CRLF +
                        "Connection: close" + CRLF + CRLF
                    out.write(streamHeader.toByteArray())
                    out.flush()
                    socket.soTimeout = 0
                    val conn = ClientConnection(socket, out, ip, isReady = true)
                    clients.add(conn)
                    while (running && !socket.isClosed) Thread.sleep(500)
                    clients.remove(conn)
                }
                path.startsWith("/admin") -> {
                    val adminCode = path.substringAfter("admin=", "").substringBefore("&")
                    if (adminCode != accessCode) {
                        out.write(buildResponse(401, "text/plain", "unauthorized".toByteArray()))
                        socket.close(); return
                    }
                    val action = path.substringAfter("action=", "").substringBefore("&")
                    val result = when(action) {
                        "reset_code" -> """{"code":"${resetCode()}"}"""
                        "disconnect_all" -> { disconnectAll(); """{"msg":"ok"}""" }
                        "toggle_stream" -> { streamEnabled = !streamEnabled; """{"enabled":$streamEnabled}""" }
                        else -> """{"clients":${clients.size},"code":"$accessCode","enabled":$streamEnabled}"""
                    }
                    out.write(buildResponse(200, "application/json", result.toByteArray()))
                    socket.close()
                }
                path.startsWith("/info") -> {
                    val fps = if (System.currentTimeMillis() - startTime > 0)
                        (frameCount * 1000 / (System.currentTimeMillis() - startTime)).toString() else "0"
                    val j = """{"clients":${clients.size},"frames":$frameCount,"fps":$fps}"""
                    out.write(buildResponse(200, "application/json", j.toByteArray()))
                    socket.close()
                }
                else -> {
                    val html = buildHtml()
                    out.write(buildResponse(200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8)))
                    socket.close()
                }
            }
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun sendFrame(jpegBytes: ByteArray) {
        frameCount++
        if (clients.isEmpty()) return
        val header = ("--frame" + CRLF + "Content-Type: image/jpeg" + CRLF +
            "Content-Length: " + jpegBytes.size + CRLF + CRLF).toByteArray(Charsets.UTF_8)
        val footer = CRLF.toByteArray(Charsets.UTF_8)
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
                toRemove.add(conn)
                try { conn.socket.close() } catch (_: Exception) {}
            }
        }
        clients.removeAll(toRemove)
    }

    fun getClientCount() = clients.size

    fun stop() {
        running = false
        disconnectAll()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun buildHtml(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang='ar' dir='rtl'><head>")
        sb.append("<meta charset='UTF-8'>")
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
        sb.append("<title>Night Vision</title>")
        sb.append("<style>*{margin:0;padding:0;box-sizing:border-box}")
        sb.append("body{background:#0a0a0a;color:#fff;font-family:monospace;")
        sb.append("display:flex;flex-direction:column;align-items:center;padding:16px}")
        sb.append("</style></head><body>")
        sb.append("<h2 style='color:#00e676;margin:16px'>Night Vision Camera</h2>")
        sb.append("<div id='lg' style='background:#1e1e1e;border-radius:12px;")
        sb.append("padding:20px;width:100%;max-width:400px'>")
        sb.append("<p style='color:#78909c;margin-bottom:12px;text-align:center'>")
        sb.append("ادخل كود المشاهدة</p>")
        sb.append("<input id='c' type='number' placeholder='0000' ")
        sb.append("style='width:100%;background:#111;border:1px solid #333;")
        sb.append("color:#fff;padding:12px;border-radius:8px;font-size:1.4em;")
        sb.append("text-align:center;letter-spacing:8px'>")
        sb.append("<button onclick='go()' style='width:100%;background:#00e676;")
        sb.append("color:#000;border:none;padding:14px;border-radius:8px;")
        sb.append("font-size:1em;font-weight:bold;cursor:pointer;margin-top:8px'>")
        sb.append("مشاهدة</button>")
        sb.append("<p id='e' style='color:#f44;margin-top:8px;text-align:center'></p>")
        sb.append("</div>")
        sb.append("<div id='sv' style='display:none;width:100%'>")
        sb.append("<img id='s' style='width:100%;border:2px solid #00e676;")
        sb.append("border-radius:8px;margin-top:12px'>")
        sb.append("</div>")
        sb.append("<script>")
        sb.append("function go(){")
        sb.append("var c=document.getElementById('c').value;")
        sb.append("if(!c||c.length<4){")
        sb.append("document.getElementById('e').textContent='ادخل 4 ارقام';return;}")
        sb.append("var img=document.getElementById('s');")
        sb.append("img.src='/stream?code='+c;")
        sb.append("img.onerror=function(){")
        sb.append("document.getElementById('e').textContent='كود خاطئ او البث متوقف';")
        sb.append("document.getElementById('sv').style.display='none';")
        sb.append("document.getElementById('lg').style.display='block';};")
        sb.append("document.getElementById('lg').style.display='none';")
        sb.append("document.getElementById('sv').style.display='block';}")
        sb.append("document.getElementById('c').addEventListener('keypress',")
        sb.append("function(e){if(e.key==='Enter')go();});")
        sb.append("</script></body></html>")
        return sb.toString()
    }
}
