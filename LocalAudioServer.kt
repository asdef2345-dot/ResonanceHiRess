package com.resonance.hires

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/** Minimal LAN HTTP server for one selected audio file, including byte-range requests. */
class LocalAudioServer(private val context: Context) {
    @Volatile private var activeFile: File? = null
    @Volatile private var server: ServerSocket? = null
    private val clients = Executors.newCachedThreadPool()

    @Synchronized fun start(file: File): String {
        activeFile = file
        if (server == null || server?.isClosed == true) {
            val socket = ServerSocket(0, 16, java.net.InetAddress.getByName("0.0.0.0"))
            server = socket
            Thread({ acceptLoop(socket) }, "resonance-http-server").apply { isDaemon = true; start() }
        }
        val ip = localIpv4() ?: throw IllegalStateException("หา IPv4 ของ Wi-Fi ไม่พบ")
        return "http://$ip:${server!!.localPort}/track"
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            try { val client = ss.accept(); clients.execute { handle(client) } }
            catch (_: Exception) { if (!ss.isClosed) continue }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 8000
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) rangeHeader = line.substringAfter(':').trim()
            }
            val file = activeFile
            if (file == null || !file.exists() || parts[1].substringBefore('?') != "/track") {
                write(output, "HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
                return
            }
            val length = file.length()
            var start = 0L; var end = length - 1; var partial = false
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                try {
                    val range = rangeHeader.substringAfter('=').substringBefore(',').split('-')
                    start = range[0].toLongOrNull() ?: 0L
                    end = range.getOrNull(1)?.toLongOrNull() ?: end
                    if (start < 0 || start >= length || end < start) throw IllegalArgumentException()
                    end = minOf(end, length - 1); partial = true
                } catch (_: Exception) {
                    write(output, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nContent-Length: 0\r\n\r\n"); return
                }
            }
            val mime = when (file.extension.lowercase()) { "flac" -> "audio/flac"; "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "ogg" -> "audio/ogg"; "opus" -> "audio/ogg"; else -> "application/octet-stream" }
            val size = (end - start + 1).coerceAtLeast(0)
            val status = if (partial) "206 Partial Content" else "200 OK"
            write(output, "HTTP/1.1 $status\r\nContent-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $size\r\nConnection: close\r\n${if (partial) "Content-Range: bytes $start-$end/$length\r\n" else ""}\r\n")
            output.flush()
            if (method.equals("HEAD", true)) return
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(64 * 1024); var remaining = size
                while (remaining > 0) {
                    val count = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count); remaining -= count
                }
                output.flush()
            }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 8192) {
            val b = input.read(); if (b == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            if (b == 10) break
            if (b != 13) bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
    private fun write(out: BufferedOutputStream, text: String) = out.write(text.toByteArray(Charsets.UTF_8))

    private fun localIpv4(): String? {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in java.util.Collections.list(interfaces)) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in java.util.Collections.list(iface.inetAddresses)) {
                if (address is java.net.Inet4Address && !address.isLoopbackAddress && !address.hostAddress.startsWith("169.254.")) return address.hostAddress
            }
        }
        return null
    }

    @Synchronized fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null; activeFile = null
    }
}
