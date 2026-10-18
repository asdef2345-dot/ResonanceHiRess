package com.resonance.hires

import org.w3c.dom.Element
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

 data class SonosDevice(val name: String, val address: String, val avTransportControlUrl: String, val renderingControlUrl: String?)

object SonosController {
    fun discover(): List<SonosDevice> {
        val found = linkedMapOf<String, SonosDevice>()
        val search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n".toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.soTimeout = 350
            socket.broadcast = true
            val packet = DatagramPacket(search, search.size, InetAddress.getByName("239.255.255.250"), 1900)
            repeat(10) {
                socket.send(packet)
                val deadline = System.currentTimeMillis() + 250
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val bytes = ByteArray(8192); val response = DatagramPacket(bytes, bytes.size)
                        socket.receive(response)
                        val headers = String(response.data, 0, response.length, Charsets.UTF_8)
                        val location = headers.lineSequence().firstOrNull { it.startsWith("LOCATION:", true) }?.substringAfter(':')?.trim() ?: continue
                        if (!found.containsKey(location)) {
                            try { readDevice(location)?.let { found[location] = it } } catch (_: Exception) { }
                        }
                    } catch (_: java.net.SocketTimeoutException) { break }
                }
            }
        }
        return found.values.toList()
    }

    private fun readDevice(location: String): SonosDevice? {
        val connection = URL(location).openConnection() as HttpURLConnection
        connection.connectTimeout = 2500; connection.readTimeout = 2500
        val bytes = connection.inputStream.use { it.readBytes() }; connection.disconnect()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val xpath = XPathFactory.newInstance().newXPath()
        fun value(expression: String): String? = (xpath.evaluate(expression, doc, XPathConstants.STRING) as String).trim().takeIf { it.isNotEmpty() }
        val name = value("//*[local-name()='friendlyName']") ?: "Sonos speaker"
        val services = doc.getElementsByTagName("service")
        var av: String? = null; var rendering: String? = null
        for (i in 0 until services.length) {
            val service = services.item(i) as? Element ?: continue
            fun child(tag: String) = service.getElementsByTagName(tag).item(0)?.textContent?.trim()
            val type = child("serviceType") ?: continue
            val control = child("controlURL") ?: continue
            if (type.contains("AVTransport")) av = resolve(location, control)
            if (type.contains("RenderingControl")) rendering = resolve(location, control)
        }
        val avUrl = av ?: return null
        return SonosDevice(name, URL(location).host, avUrl, rendering)
    }

    private fun resolve(base: String, relative: String): String = URI(base).resolve(relative).toString()

    fun play(device: SonosDevice, mediaUrl: String, title: String) {
        val mime = when (title.substringAfterLast('.', "").lowercase()) { "flac" -> "audio/flac"; "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "ogg", "opus" -> "audio/ogg"; else -> "application/octet-stream" }
        val metadata = """<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>${xmlEscape(title)}</dc:title><upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo=\"http-get:*:$mime:*\">${xmlEscape(mediaUrl)}</res></item></DIDL-Lite>"""
        soap(device.avTransportControlUrl, "AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${xmlEscape(mediaUrl)}</CurrentURI><CurrentURIMetaData>${xmlEscape(metadata)}</CurrentURIMetaData>")
        soap(device.avTransportControlUrl, "AVTransport", "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    fun stop(device: SonosDevice) = soap(device.avTransportControlUrl, "AVTransport", "Stop", "<InstanceID>0</InstanceID>")

    private fun soap(url: String, service: String, action: String, arguments: String) {
        val serviceType = "urn:schemas-upnp-org:service:$service:1"
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="$serviceType">$arguments</u:$action></s:Body></s:Envelope>"""
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.connectTimeout = 4000; conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("Sonos $action HTTP $code ${response.take(250)}")
    }

    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
