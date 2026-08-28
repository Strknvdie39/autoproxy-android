package com.github.kr328.clash

import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * HTTP loopback toi gian (spec D' 3.5) — server dat tay qua `adb forward tcp:X tcp:<local_port>`:
 *   GET /health            -> {ok, version}
 *   GET /status            -> AutoProxy.statusJson() (da che)
 *   GET /probe?timeout_ms= -> GET that toi https://api.ipify.org bang socket KHONG protect
 *                             (= di qua tun nhu app thuong) -> {ok, egress_ip, ms} | {ok:false, err, ms}
 * Chi bind 127.0.0.1 (IPv4 RAW — xem chu thich o `socket`). Moi request 1 thread, Connection: close.
 */
class LocalHttpServer(val port: Int) {
    /**
     * 0.1.3 — GHIM 127.0.0.1 bang dia chi RAW. `InetAddress.getLoopbackAddress()` tra `::1`
     * khi runtime uu tien IPv6, va no KHONG xac dinh theo ROM/luot boot: may3 28/08 co
     * `[::1]:45E2 LISTEN` trong /proc/net/tcp6 con IPv4 trong tron, may khac cung ROM lai
     * binh thuong. Host do `/probe` qua `adb forward tcp:X tcp:17890` va adbd noi vao
     * loopback IPv4 => connection refused => server ket luan "proxy chet" => THAO tunnel
     * dua may ve MANG THAT trong khi tunnel dang song. Bind sai mot bit dia chi doi lay
     * mot lan LO IP: khong duoc de no phu thuoc vao ROM. Khong resolve "localhost" (no ra
     * IPv6 duoc) — dia chi raw la thu duy nhat khong the doi nghia.
     */
    private val socket = ServerSocket(port, 8, InetAddress.getByAddress(IPV4_LOOPBACK))

    @Volatile
    private var running = true

    fun start() {
        Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running) Log.w("AutoProxy http accept failed", e)
                    continue
                }
                Thread({ handle(client) }, "autoproxy-http-req").start()
            }
        }, "autoproxy-http").start()
        Log.i("AutoProxy http listening 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            // drain headers
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            val target = parts.getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            val (code, body) = when {
                parts.getOrNull(0) != "GET" -> 405 to JSONObject().put("err", "method")
                path == "/health" -> 200 to JSONObject().put("ok", true).put("version", AutoProxy.version())
                path == "/status" -> 200 to AutoProxy.statusJson()
                path == "/probe" -> 200 to probe(queryInt(query, "timeout_ms", 8_000))
                else -> 404 to JSONObject().put("err", "not_found")
            }

            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            c.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(bytes)
                flush()
            }
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
    }

    private fun queryInt(query: String, key: String, default: Int): Int =
        query.split('&').firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.toIntOrNull()?.coerceIn(1_000, 60_000) ?: default

    /**
     * Loi doc ve theo spec: proxy_reset | timeout | dns | http_<code> | <message>.
     *
     * 0.1.1: deadline TONG = timeout_ms. Truoc day connectTimeout/readTimeout chi tran
     * TUNG BUOC (DNS + TLS + connect + read cong don -> do 10.06s voi timeout_ms=8000).
     * Fetch chay trong thread con daemon, join(timeout_ms): het gio -> tra
     * {ok:false, err:"timeout"} NGAY, ket qua den muon bi BO (thread con tu chet theo
     * timeout per-op, leak co tran). `ms` chi dat o tang ngoai, mot lan.
     */
    private fun probe(timeoutMs: Int): JSONObject {
        val t0 = SystemClock.elapsedRealtime()
        val result = AtomicReference<JSONObject?>()
        val worker = Thread({ result.set(fetchProbe(timeoutMs)) }, "autoproxy-probe")
        worker.isDaemon = true
        worker.start()
        worker.join(timeoutMs.toLong())
        val body = result.get() ?: JSONObject().put("ok", false).put("err", "timeout")
        return body.put("ms", SystemClock.elapsedRealtime() - t0)
    }

    private fun fetchProbe(timeoutMs: Int): JSONObject {
        val out = JSONObject()
        try {
            val conn = URL(PROBE_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "AutoProxy/" + AutoProxy.version())
            try {
                val code = conn.responseCode
                if (code != 200) {
                    out.put("ok", false).put("err", "http_$code")
                } else {
                    val ip = conn.inputStream.bufferedReader().readText().trim()
                    out.put("ok", true).put("egress_ip", ip)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            out.put("ok", false).put("err", "timeout")
        } catch (e: UnknownHostException) {
            out.put("ok", false).put("err", "dns")
        } catch (e: SSLException) {
            out.put("ok", false).put("err", classify(e.message))
        } catch (e: SocketException) {
            out.put("ok", false).put("err", classify(e.message))
        } catch (e: Exception) {
            out.put("ok", false).put("err", e.javaClass.simpleName + ":" + e.message)
        }
        return out
    }

    private fun classify(msg: String?): String {
        val m = msg?.lowercase() ?: return "proxy_reset"
        return when {
            "reset" in m || "eof" in m || "closed" in m || "broken pipe" in m -> "proxy_reset"
            "timed out" in m -> "timeout"
            "unreachable" in m || "refused" in m -> "unreachable"
            else -> "io:$msg"
        }
    }

    companion object {
        const val PROBE_URL = "https://api.ipify.org"

        /** 127.0.0.1 — xem chu thich o `socket`. */
        private val IPV4_LOOPBACK = byteArrayOf(127, 0, 0, 1)
    }
}
