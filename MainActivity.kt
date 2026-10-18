package com.resonance.hires

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var trackLabel: TextView
    private lateinit var player: ExoPlayer
    private lateinit var streamServer: LocalAudioServer
    private val executor = Executors.newSingleThreadExecutor()
    private var selectedFile: File? = null
    private var sonosDevices: List<SonosDevice> = emptyList()
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        streamServer = LocalAudioServer(this)
        buildUi()
        status.text = "พร้อมใช้งาน • เลือกไฟล์เพลงเพื่อเริ่มต้น"
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 24)
            setBackgroundColor(0xFF10131A.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = "RESONANCE\nHI-RES AUDIO"
            textSize = 28f; setTextColor(0xFFF3F5FA.toInt()); gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 8)
        }
        val subtitle = TextView(this).apply {
            text = "Local player · Wi-Fi streaming to Sonos"
            textSize = 14f; setTextColor(0xFF9DA7BA.toInt()); setPadding(0, 0, 0, 22)
        }
        trackLabel = TextView(this).apply {
            text = "ยังไม่ได้เลือกเพลง"; textSize = 17f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 18, 0, 14)
        }
        status = TextView(this).apply {
            textSize = 13f; setTextColor(0xFFB8C2D6.toInt()); setPadding(0, 18, 0, 20)
        }
        content.addView(title); content.addView(subtitle); content.addView(trackLabel)
        content.addView(actionButton("เลือกไฟล์เพลง (FLAC / WAV / MP3)") { chooseAudio() })
        content.addView(actionButton("เล่นบนมือถือ") { playLocal() })
        content.addView(actionButton("หยุด / เล่นต่อ") { if (player.isPlaying) player.pause() else player.play() })
        content.addView(actionButton("ค้นหาลำโพง Sonos บน Wi-Fi") { discoverSonos() })
        content.addView(actionButton("ส่งเพลงที่เลือกไป Sonos") { sendToSonos() })
        content.addView(actionButton("หยุดเล่นบน Sonos") { stopSonos() })
        content.addView(status)
        val note = TextView(this).apply {
            text = "รุ่นทดลอง 0.1.0-alpha\nการส่ง Sonos ใช้ UPnP AVTransport และ HTTP ภายในเครือข่ายเดียวกัน การรองรับไฟล์/คุณภาพเสียงต้องทดสอบกับลำโพงจริง"
            textSize = 12f; setTextColor(0xFF778196.toInt()); setPadding(0, 12, 0, 12)
        }
        content.addView(note)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8
        }
    }

    private fun chooseAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQUEST_AUDIO)
    }

    @Deprecated("Uses activity result API for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_AUDIO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "กำลังเตรียมไฟล์เพลง…"
        executor.execute {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "เปิดไฟล์ไม่ได้" }
                    val name = queryDisplayName(uri) ?: "track-${System.currentTimeMillis()}"
                    val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
                    val ext = safeName.substringAfterLast('.', "audio")
                    val destination = File(cacheDir, "resonance_track.$ext")
                    destination.outputStream().use { output -> input.copyTo(output) }
                    selectedFile = destination
                    runOnUiThread {
                        trackLabel.text = "เพลงที่เลือก: $name\nขนาดไฟล์: ${destination.length() / 1024} KB"
                        status.text = "พร้อมเล่น • ไฟล์ถูกคัดลอกไปยัง cache เพื่อเปิดให้ Sonos เข้าถึงผ่าน Wi-Fi"
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(destination)))
                        player.prepare()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "อ่านไฟล์ไม่สำเร็จ: ${e.message}" }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun playLocal() {
        if (selectedFile == null) { status.text = "กรุณาเลือกไฟล์เพลงก่อน"; return }
        player.play(); status.text = "กำลังเล่นบนมือถือ"
    }

    private fun discoverSonos() {
        status.text = "กำลังค้นหา Sonos ในเครือข่ายเดียวกัน…"
        executor.execute {
            try {
                val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("resonance-ssdp").apply { setReferenceCounted(false); acquire() }
                val found = SonosController.discover()
                sonosDevices = found
                runOnUiThread {
                    status.text = if (found.isEmpty()) {
                        "ไม่พบ Sonos: ตรวจสอบว่าโทรศัพท์กับลำโพงอยู่ Wi-Fi เดียวกัน และ router ไม่บล็อก multicast"
                    } else {
                        "พบ Sonos ${found.size} เครื่อง: ${found.joinToString { it.name }}\nกด ‘ส่งเพลงที่เลือกไป Sonos’ เพื่อส่งไปยังเครื่องแรกที่พบ"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "ค้นหา Sonos ไม่สำเร็จ: ${e.message}" }
            } finally {
                try { multicastLock?.release() } catch (_: Exception) { }
                multicastLock = null
            }
        }
    }

    private fun sendToSonos() {
        val file = selectedFile
        if (file == null) { status.text = "กรุณาเลือกไฟล์เพลงก่อน"; return }
        if (sonosDevices.isEmpty()) { status.text = "กรุณาค้นหา Sonos ก่อน"; return }
        status.text = "กำลังเริ่มเซิร์ฟเวอร์เพลงและส่งคำสั่งไป Sonos…"
        executor.execute {
            try {
                val url = streamServer.start(file)
                SonosController.play(sonosDevices.first(), url, file.name)
                runOnUiThread { status.text = "ส่งคำสั่งเล่นไป ${sonosDevices.first().name} แล้ว\nURL: $url\nหากไม่มีเสียง ให้ตรวจสอบรูปแบบไฟล์และเครือข่าย" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "ส่งไป Sonos ไม่สำเร็จ: ${e.message}" }
            }
        }
    }

    private fun stopSonos() {
        if (sonosDevices.isEmpty()) { status.text = "ค้นหา Sonos ก่อน"; return }
        executor.execute {
            try { SonosController.stop(sonosDevices.first()); runOnUiThread { status.text = "ส่งคำสั่งหยุดไป ${sonosDevices.first().name} แล้ว" } }
            catch (e: Exception) { runOnUiThread { status.text = "หยุด Sonos ไม่สำเร็จ: ${e.message}" } }
        }
    }

    override fun onDestroy() {
        player.release(); streamServer.stop(); executor.shutdownNow()
        try { multicastLock?.release() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object { private const val REQUEST_AUDIO = 4001 }
}
