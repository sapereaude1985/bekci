package com.bekci.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs on the phone you carry. Keeps a streaming connection to the private
 * ntfy topic and shows a notification (with the decrypted photo) on motion.
 */
class WatchService : Service() {

    companion object {
        @Volatile var running = false
    }

    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notif.channels(this)
        val n = Notif.status(this, "Uyarılar açık", "Evdeki kameradan gelen hareketler bildirilecek.")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(2, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = Prefs.code(this)
        if (code == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (thread == null) {
            running = true
            val topic = Pairing.topic(code)
            thread = Thread { loop(topic, code) }.also { it.start() }
        }
        return START_STICKY
    }

    private fun loop(topic: String, code: String) {
        var backoff = 2000L
        while (running) {
            try {
                val last = Prefs.p(this).getString("lastId", null)
                val q = if (last != null) "?since=$last" else ""
                val c = URL(Ntfy.HOST + topic + "/json" + q).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15000
                c.readTimeout = 120000 // ntfy sends a keepalive about every 45 s
                c.inputStream.bufferedReader().use { r ->
                    backoff = 2000L
                    while (running) {
                        val line = r.readLine() ?: break
                        handle(line, code)
                    }
                }
            } catch (e: Exception) {
            } finally {
                try { conn?.disconnect() } catch (e: Exception) { }
                conn = null
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
            backoff = minOf(backoff * 2, 60_000L)
        }
    }

    private fun handle(line: String, code: String) {
        val o = try { JSONObject(line) } catch (e: Exception) { return }
        if (o.optString("event") != "message") return
        val id = o.optString("id")
        val time = o.optLong("time", System.currentTimeMillis() / 1000) * 1000
        if (id.isNotEmpty()) Prefs.p(this).edit().putString("lastId", id).apply()

        val msg = o.optString("message")
        val att = o.optJSONObject("attachment")
        if (att == null && msg == "start") {
            Notif.alert(this, "Kamera izlemeye başladı", fmtTime(time), null, false)
            return
        }
        if (att == null && msg == "stop") {
            Notif.alert(this, "Kamera durduruldu", fmtTime(time), null, false)
            return
        }

        var bmp: Bitmap? = null
        if (att != null) {
            try {
                val jpg = Pairing.decrypt(code, Ntfy.download(att.getString("url")))
                File(filesDir, "last.jpg").writeBytes(jpg)
                bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
            } catch (e: Exception) { }
        }
        Prefs.p(this).edit().putLong("lastTime", time).apply()
        Notif.alert(this, "Evde hareket var", fmtTime(time), bmp, true)
    }

    override fun onDestroy() {
        running = false
        val c = conn
        if (c != null) Thread { try { c.disconnect() } catch (e: Exception) { } }.start()
        thread?.interrupt()
        thread = null
        super.onDestroy()
    }
}
