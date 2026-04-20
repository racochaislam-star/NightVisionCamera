package com.nightvision.camera.streaming

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MjpegServer(private val port: Int = 8080) {

    private val TAG = "MjpegServer"
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()
    private val executor = Executors.newCachedThreadPool()

    @Volatile var running = false
    @Volatile var accessCode: String = generateCode()
    @Volatile var streamEnabled: Boolean = true
    private var frameCount = 0L
    private var startTime = 0L
    private var lastFrame: ByteArray? = null

    data class ClientConnection(
        val socket: Socket,
        val outputStream: OutputStream,
        val ip: String,
        val id: String = UUID.randomUUID().toString().take(8),
        var isReady: Boolean = false,
        val connectedAt: Long = System.currentTimeMillis()
    )

    fun generateCode(): String {
        return (1000..9999).random().toString()
    }

    fun resetCode(): String {
        accessCode = generateCode()
        disconnectAll()
        return accessCode
    }

    fun disconnectAll() {
        clients.forEach { try { it.socket.close() } catch (_: Exception) {} }
        clients.clear()
        Log.i(TAG, "All clients disconnected")
    }

    fun disconnectClient(clientId: String) {
        val client = clients.find { it.id == clientId }
        client?.let {
            try { it.socket.close() } catch (_: Exception) {}
            clients.remove(it)
            Log.i(TAG, "Client $clientId disconnected")
        }
    }

    fun getConnectedClients(): List<Map<String, String>> {
        return clients.map { mapOf(
            "id" to it.id,
            "ip" to it.ip,
            "since" to "${(System.currentTimeMillis() - it.connectedAt) / 1000}s"
        )}
    }

    fun start() {
        if (running) return
        running = true
        startTime = System.currentTimeMillis()
        executor.execute {
            try {
                serverSocket = ServerSocket(port).apply { reuseAddress = true }
                Log.i(TAG, "Server started on port $port, code: $accessCode")
                while (running && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
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
            val ip = socket.inetAddress.hostAddress ?: "unknown"
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()
            val buffer = ByteArray(4096)
            var n = 0
            try { n = inputStream.read(buffer) } catch (e: Exception) {}
            if (n <= 0) { socket.close(); return }
            val request = String(buffer, 0, n)
            val firstLine = request.lines().firstOrNull() ?: ""
            val path = firstLine.split(" ").getOrNull(1) ?: "/"

            // Extract code from URL params
            val urlCode = extractParam(path, "code")

            when {
                path.startsWith("/stream") -> {
                    if (!streamEnabled) {
                        sendError(outputStream, 503, "البث متوقف مؤقتاً")
                        socket.close(); return
                    }
                    if (urlCode != accessCode) {
                        sendError(outputStream, 401, "كود خاطئ أو منتهي الصلاحية")
                        socket.close(); return
                    }
                    val response = "HTTP/1.1 200 OK
Content-Type: multipart/x-mixed-replace; boundary=frame
Cache-Control: no-cache
Access-Control-Allow-Origin: *
Connection: close

"
                    outputStream.write(response.toByteArray())
                    outputStream.flush()
                    socket.soTimeout = 0
                    val conn = ClientConnection(socket, outputStream, ip, isReady = true)
                    clients.add(conn)
                    Log.i(TAG, "Client $ip connected. Total: ${clients.size}")
                    while (running && !socket.isClosed) { Thread.sleep(500) }
                    clients.remove(conn)
                }
                path.startsWith("/admin") -> {
                    val adminCode = extractParam(path, "admin")
                    if (adminCode != accessCode) {
                        sendError(outputStream, 401, "غير مصرح")
                        socket.close(); return
                    }
                    val action = extractParam(path, "action")
                    val result = when(action) {
                        "reset_code" -> { val c = resetCode(); """{"code":"$c","msg":"تم تغيير الكود"}""" }
                        "disconnect_all" -> { disconnectAll(); """{"msg":"تم قطع جميع المشاهدين"}""" }
                        "toggle_stream" -> { streamEnabled = !streamEnabled; """{"enabled":$streamEnabled}""" }
                        "clients" -> { val c = getConnectedClients(); """{"clients":$c,"count":${c.size}}""" }
                        else -> """{"clients":${getConnectedClients()},"code":"$accessCode","enabled":$streamEnabled}"""
                    }
                    val resp = "HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: ${result.length}
Access-Control-Allow-Origin: *

$result"
                    outputStream.write(resp.toByteArray())
                    socket.close()
                }
                path.startsWith("/info") -> {
                    val fps = if (System.currentTimeMillis() - startTime > 0)
                        (frameCount * 1000 / (System.currentTimeMillis() - startTime)).toString() else "0"
                    val json = """{"clients":${clients.size},"frames":$frameCount,"fps":$fps,"enabled":$streamEnabled}"""
                    val resp = "HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: ${json.length}
Access-Control-Allow-Origin: *

$json"
                    outputStream.write(resp.toByteArray())
                    socket.close()
                }
                else -> {
                    val html = buildViewerHtml()
                    val resp = "HTTP/1.1 200 OK
Content-Type: text/html; charset=utf-8
Content-Length: ${html.toByteArray().size}
Connection: close

$html"
                    outputStream.write(resp.toByteArray(Charsets.UTF_8))
                    socket.close()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        val html = "<html><body style='background:#000;color:#f44;font-family:monospace;padding:40px'><h2>$code</h2><p>$msg</p></body></html>"
        val resp = "HTTP/1.1 $code Error
Content-Type: text/html
Content-Length: ${html.length}

$html"
        try { out.write(resp.toByteArray()) } catch (_: Exception) {}
    }

    private fun extractParam(path: String, key: String): String {
        val query = path.substringAfter("?", "")
        return query.split("&").find { it.startsWith("$key=") }?.substringAfter("=") ?: ""
    }

    fun sendFrame(jpegBytes: ByteArray) {
        lastFrame = jpegBytes
        frameCount++
        if (clients.isEmpty()) return
        val header = "--frame
Content-Type: image/jpeg
Content-Length: ${jpegBytes.size}

".toByteArray(Charsets.US_ASCII)
        val footer = "
".toByteArray(Charsets.US_ASCII)
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

    private fun buildViewerHtml(): String = """<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Night Vision - مشاهدة البث</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{background:#0a0a0a;color:#fff;font-family:monospace;display:flex;flex-direction:column;align-items:center;min-height:100vh;padding:16px}
h1{color:#00e676;margin-bottom:8px}
.card{background:#1e1e1e;border-radius:12px;padding:20px;width:100%;max-width:400px;margin:8px 0}
input{width:100%;background:#111;border:1px solid #333;color:#fff;padding:12px;border-radius:8px;font-size:1.2em;text-align:center;letter-spacing:8px;margin:8px 0}
.btn{width:100%;background:#00e676;color:#000;border:none;padding:14px;border-radius:8px;font-size:1em;font-weight:bold;cursor:pointer;margin-top:8px}
#streamDiv{display:none;width:100%}
#stream{width:100%;border:2px solid #00e676;border-radius:8px}
.dot{display:inline-block;width:8px;height:8px;background:#00e676;border-radius:50%;margin-left:6px;animation:blink 1s infinite}
@keyframes blink{0%,100%{opacity:1}50%{opacity:0.2}}
.err{color:#f44;font-size:.9em;margin-top:8px}
</style>
</head>
<body>
<h1>🌙 Night Vision</h1>
<div class="card" id="loginDiv">
  <p style="color:#78909c;margin-bottom:12px;text-align:center">أدخل كود المشاهدة</p>
  <input type="number" id="codeInput" placeholder="0000" maxlength="4">
  <button class="btn" onclick="connect()">▶ مشاهدة البث</button>
  <p class="err" id="errMsg"></p>
</div>
<div id="streamDiv">
  <p style="color:#00e676;text-align:center;margin-bottom:8px"><span class="dot"></span> بث مباشر</p>
  <img id="stream" alt="stream">
</div>
<script>
function connect(){
  const code=document.getElementById('codeInput').value;
  if(!code||code.length<4){document.getElementById('errMsg').textContent='أدخل كوداً صحيحاً';return;}
  const url='/stream?code='+code;
  document.getElementById('stream').src=url;
  document.getElementById('stream').onerror=function(){
    document.getElementById('errMsg').textContent='كود خاطئ أو البث متوقف';
    document.getElementById('streamDiv').style.display='none';
    document.getElementById('loginDiv').style.display='block';
  };
  document.getElementById('loginDiv').style.display='none';
  document.getElementById('streamDiv').style.display='block';
}
document.getElementById('codeInput').addEventListener('keypress',function(e){if(e.key==='Enter')connect();});
</script>
</body>
</html>""".trimIndent()
}
