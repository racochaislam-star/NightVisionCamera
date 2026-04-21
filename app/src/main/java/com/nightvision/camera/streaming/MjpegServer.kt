package com.nightvision.camera.streaming

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MjpegServer(private val port: Int = 8080) {

    private val TAG = "MjpegServer"
    private var server: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val clients = CopyOnWriteArrayList<Client>()

    @Volatile var running = false
    @Volatile var pin: String = newPin()
    @Volatile var enabled = true
    private var frames = 0L
    private var startMs = 0L

    data class Client(
        val socket: Socket,
        val out: OutputStream,
        val ip: String,
        val id: String = UUID.randomUUID().toString().take(6),
        @Volatile var ready: Boolean = false,
        val ts: Long = System.currentTimeMillis()
    )

    fun newPin(): String = (1000..9999).random().toString()

    fun resetPin(): String { pin = newPin(); kickAll(); return pin }

    fun kickAll() {
        clients.forEach { safeClose(it.socket) }
        clients.clear()
    }

    fun kick(id: String) {
        clients.find { it.id == id }?.let { safeClose(it.socket); clients.remove(it) }
    }

    fun clientCount() = clients.size

    fun start() {
        if (running) return
        running = true
        startMs = System.currentTimeMillis()
        executor.execute {
            try {
                server = ServerSocket(port).apply { reuseAddress = true }
                Log.i(TAG, "MJPEG server on :$port  pin=$pin")
                while (running) {
                    try { executor.execute { handleClient(server!!.accept()) } }
                    catch (e: Exception) { if (running) Log.e(TAG, e.message ?: "") }
                }
            } catch (e: Exception) { Log.e(TAG, e.message ?: "") }
        }
    }

    fun stop() {
        running = false
        kickAll()
        safeClose(server)
    }

    fun sendFrame(jpeg: ByteArray) {
        if (!running || clients.isEmpty()) return
        frames++
        val sep = "\r\n"
        val hdr = ("--frame" + sep +
                "Content-Type: image/jpeg" + sep +
                "Content-Length: " + jpeg.size + sep + sep).toByteArray(Charsets.UTF_8)
        val ftr = sep.toByteArray(Charsets.UTF_8)
        val dead = mutableListOf<Client>()
        for (c in clients) {
            if (!c.ready) continue
            try {
                synchronized(c.out) {
                    c.out.write(hdr); c.out.write(jpeg); c.out.write(ftr); c.out.flush()
                }
            } catch (e: Exception) { dead.add(c); safeClose(c.socket) }
        }
        clients.removeAll(dead)
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val ip = socket.inetAddress.hostAddress ?: "?"
            val buf = ByteArray(4096)
            val n = try { socket.getInputStream().read(buf) } catch (e: Exception) { -1 }
            if (n <= 0) { safeClose(socket); return }
            val req = String(buf, 0, n)
            val path = req.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1) ?: "/"
            val out = socket.getOutputStream()

            when {
                path.startsWith("/stream") -> {
                    val code = queryParam(path, "code")
                    if (!enabled) { sendError(out, 503, "البث متوقف"); safeClose(socket); return }
                    if (code != pin) { sendError(out, 401, "كود خاطئ"); safeClose(socket); return }
                    val hdr = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n")
                        append("Cache-Control: no-cache\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    out.write(hdr.toByteArray(Charsets.UTF_8)); out.flush()
                    socket.soTimeout = 0
                    val client = Client(socket, out, ip, ready = true)
                    clients.add(client)
                    Log.i(TAG, "Client connected: $ip  total=${clients.size}")
                    while (running && !socket.isClosed) Thread.sleep(300)
                    clients.remove(client)
                }

                path.startsWith("/admin") -> {
                    val adminPin = queryParam(path, "admin")
                    if (adminPin != pin) { sendError(out, 401, "Unauthorized"); safeClose(socket); return }
                    val action = queryParam(path, "action")
                    val result = when (action) {
                        "reset_code" -> """{"pin":"${resetPin()}","msg":"done"}"""
                        "kick_all" -> { kickAll(); """{"msg":"all kicked"}""" }
                        "toggle" -> { enabled = !enabled; """{"enabled":$enabled}""" }
                        else -> {
                            val fps = if (System.currentTimeMillis() - startMs > 0)
                                frames * 1000 / (System.currentTimeMillis() - startMs) else 0
                            """{"clients":${clients.size},"pin":"$pin","enabled":$enabled,"fps":$fps}"""
                        }
                    }
                    sendJson(out, result)
                    safeClose(socket)
                }

                path.startsWith("/info") -> {
                    val fps = if (System.currentTimeMillis() - startMs > 0)
                        frames * 1000 / (System.currentTimeMillis() - startMs) else 0
                    sendJson(out, """{"clients":${clients.size},"frames":$frames,"fps":$fps}""")
                    safeClose(socket)
                }

                else -> {
                    val html = buildViewerPage()
                    val hdr = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }
                    out.write(hdr.toByteArray(Charsets.UTF_8))
                    out.write(html.toByteArray(Charsets.UTF_8))
                    safeClose(socket)
                }
            }
        } catch (e: Exception) { safeClose(socket) }
    }

    private fun queryParam(path: String, key: String): String =
        path.substringAfter("$key=", "").substringBefore("&").substringBefore(" ")

    private fun sendError(out: OutputStream, code: Int, msg: String) {
        val body = "<html><body style='background:#111;color:#f44;font-family:monospace;padding:40px'><h2>$code</h2><p>$msg</p></body></html>"
        val hdr = "HTTP/1.1 $code\r\nContent-Type: text/html\r\nContent-Length: ${body.length}\r\n\r\n"
        try { out.write(hdr.toByteArray()); out.write(body.toByteArray()) } catch (_: Exception) {}
    }

    private fun sendJson(out: OutputStream, json: String) {
        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.length}\r\n\r\n"
        try { out.write(hdr.toByteArray()); out.write(json.toByteArray()) } catch (_: Exception) {}
    }

    private fun safeClose(c: Any?) {
        try { when (c) { is Socket -> c.close(); is ServerSocket -> c.close() } } catch (_: Exception) {}
    }

    private fun buildViewerPage(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang='ar' dir='rtl'><head>")
        sb.append("<meta charset='UTF-8'>")
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
        sb.append("<title>Night Vision - مشاهدة</title>")
        sb.append("<style>")
        sb.append("*{margin:0;padding:0;box-sizing:border-box}")
        sb.append("body{background:#0a0a0a;color:#fff;font-family:monospace;min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:20px}")
        sb.append("h1{color:#00e676;margin-bottom:20px;font-size:1.3em}")
        sb.append(".card{background:#1e1e1e;border-radius:12px;padding:20px;width:100%;max-width:420px}")
        sb.append("label{color:#78909c;font-size:.85em;display:block;margin-bottom:6px}")
        sb.append("input{width:100%;background:#111;border:1px solid #333;color:#fff;padding:14px;")
        sb.append("border-radius:8px;font-size:1.6em;text-align:center;letter-spacing:12px;margin-bottom:12px}")
        sb.append("input:focus{outline:none;border-color:#00e676}")
        sb.append("button{width:100%;background:#00e676;color:#000;border:none;padding:14px;")
        sb.append("border-radius:8px;font-size:1em;font-weight:bold;cursor:pointer}")
        sb.append(".err{color:#ff1744;font-size:.9em;margin-top:8px;text-align:center;min-height:20px}")
        sb.append("#sv{display:none;width:100%;margin-top:16px}")
        sb.append("#st{width:100%;border:2px solid #00e676;border-radius:8px}")
        sb.append(".dot{display:inline-block;width:8px;height:8px;background:#00e676;border-radius:50%;margin-left:6px;animation:b 1s infinite}")
        sb.append("@keyframes b{0%,100%{opacity:1}50%{opacity:.2}}")
        sb.append("</style></head><body>")
        sb.append("<h1>🌙 Night Vision Camera</h1>")
        sb.append("<div class='card' id='lg'>")
        sb.append("<label>كود المشاهدة (4 أرقام)</label>")
        sb.append("<input id='pin' type='number' placeholder='0000' maxlength='4'>")
        sb.append("<button onclick='go()'>▶ بدء المشاهدة</button>")
        sb.append("<p class='err' id='err'></p>")
        sb.append("</div>")
        sb.append("<div id='sv'>")
        sb.append("<p style='color:#00e676;text-align:center;margin-bottom:8px'><span class='dot'></span>بث مباشر</p>")
        sb.append("<img id='st' alt='stream'>")
        sb.append("<button onclick='stop()' style='margin-top:12px;background:#ff1744;color:#fff;border:none;")
        sb.append("padding:10px;border-radius:8px;width:100%;cursor:pointer'>⏹ قطع الاتصال</button>")
        sb.append("</div>")
        sb.append("<script>")
        sb.append("function go(){")
        sb.append("var p=document.getElementById('pin').value.trim();")
        sb.append("if(p.length<4){document.getElementById('err').textContent='أدخل 4 أرقام';return;}")
        sb.append("var img=document.getElementById('st');")
        sb.append("img.src='/stream?code='+p;")
        sb.append("img.onerror=function(){document.getElementById('err').textContent='كود خاطئ أو البث متوقف';")
        sb.append("document.getElementById('sv').style.display='none';")
        sb.append("document.getElementById('lg').style.display='block';};")
        sb.append("document.getElementById('lg').style.display='none';")
        sb.append("document.getElementById('sv').style.display='block';}")
        sb.append("function stop(){document.getElementById('st').src='';")
        sb.append("document.getElementById('sv').style.display='none';")
        sb.append("document.getElementById('lg').style.display='block';}")
        sb.append("document.getElementById('pin').addEventListener('keypress',function(e){if(e.key==='Enter')go();});")
        sb.append("</script></body></html>")
        return sb.toString()
    }
}
