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
        const val OFFLINE_MS = 12 * 60_000L
        /** Set when the user turns alerts on: start from "now", don't replay what came while they were off. */
        const val EXTRA_FRESH = "fresh"
    }

    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null
    private var checker: Thread? = null
    @Volatile private var lastLine = 0L

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
        if (intent?.getBooleanExtra(EXTRA_FRESH, false) == true) {
            Prefs.p(this).edit().remove("lastId").putLong("alertsSince", System.currentTimeMillis()).apply()
        }
        if (thread == null) {
            running = true
            val topic = Pairing.topic(code)
            thread = Thread { loop(topic, code) }.also { it.start() }
            checker = Thread { checkLoop() }.also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    private fun loop(topic: String, code: String) {
        var backoff = 2000L
        while (running) {
            try {
                val p = Prefs.p(this)
                val last = p.getString("lastId", null)
                // Reconnects catch up from the last message; a fresh start only from when alerts were turned on.
                val q = if (last != null) "?since=$last"
                else "?since=" + (p.getLong("alertsSince", System.currentTimeMillis()) / 1000L)
                val c = URL(Ntfy.HOST + topic + "/json" + q).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15000
                c.readTimeout = 120000 // ntfy sends a keepalive about every 45 s
                c.inputStream.bufferedReader().use { r ->
                    backoff = 2000L
                    while (running) {
                        val line = r.readLine() ?: break
                        lastLine = System.currentTimeMillis()
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
        // Anything older than the moment alerts were turned on is ignored (ntfy time has 1 s resolution).
        if (time + 1000L < Prefs.p(this).getLong("alertsSince", 0L)) return

        val msg = o.optString("message")
        val att = o.optJSONObject("attachment")
        seen(time, msg, att == null)
        if (att == null && msg.startsWith("hb_")) return
        if (att == null && msg.startsWith("sens_")) {
            val v = msg.removePrefix("sens_").toIntOrNull()?.coerceIn(1, 10) ?: return
            Prefs.p(this).edit().putInt("camSens", v).putLong("camSensPending", 0L).apply()
            return
        }
        if (att == null && (msg == "motion_on" || msg == "motion_off")) {
            val on = msg == "motion_on"
            Prefs.p(this).edit().putBoolean("camMotion", on).putLong("camMotionPending", 0L).apply()
            Notif.alert(this, if (on) "Hareket algılama açıldı" else "Hareket algılama kapatıldı", fmtTime(time), null, false)
            return
        }
        if (att == null && msg == "start") {
            Prefs.p(this).edit().putBoolean("camMotion", true).apply()
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
                if (Prefs.p(this).getBoolean("galeri", true)) Gallery.save(this, jpg, Gallery.MOTION, time)
                bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size)
            } catch (e: Exception) { }
        }
        Prefs.p(this).edit().putLong("lastTime", time).apply()
        Notif.alert(this, "Evde hareket var", fmtTime(time), bmp, true)
    }

    // ---------- offline / battery watch ----------

    private fun seen(time: Long, msg: String, text: Boolean) {
        val p = Prefs.p(this)
        val e = p.edit()
        if (time > p.getLong("camLastSeen", 0L)) e.putLong("camLastSeen", time)
        val recent = System.currentTimeMillis() - time < OFFLINE_MS
        when {
            text && msg == "stop" -> e.putBoolean("camRunning", false).putBoolean("camOfflineAlerted", false)
            else -> e.putBoolean("camRunning", true)
        }
        if (text && msg.startsWith("hb_")) {
            val parts = msg.split("_")
            val pct = parts.getOrNull(1)?.toIntOrNull() ?: -1
            val chg = parts.getOrNull(2) == "1"
            e.putBoolean("camHbSeen", true).putInt("camBattery", pct).putBoolean("camCharging", chg)
            if (recent) {
                if (pct in 0..14 && !chg && !p.getBoolean("camLowBatAlerted", false)) {
                    e.putBoolean("camLowBatAlerted", true)
                    Notif.alert(this, "Evdeki kameranın pili azaldı (%$pct)", "Şarja takılı değil. Pil biterse kamera çevrimdışı kalır.", null, true)
                } else if (chg || pct > 25) e.putBoolean("camLowBatAlerted", false)
            }
        }
        if (recent && p.getBoolean("camOfflineAlerted", false) && !(text && msg == "stop")) {
            e.putBoolean("camOfflineAlerted", false)
            Notif.alert(this, "Evdeki kamera yeniden çevrimiçi", fmtTime(time), null, false)
        }
        e.apply()
    }

    private fun checkLoop() {
        while (running) {
            try { Thread.sleep(60_000L) } catch (e: InterruptedException) { return }
            if (!running) return
            val p = Prefs.p(this)
            val now = System.currentTimeMillis()
            // Only judge when our own connection is healthy, so our outage isn't blamed on the camera.
            if (now - lastLine > 150_000L) continue
            if (!p.getBoolean("camHbSeen", false) || !p.getBoolean("camRunning", false)) continue
            val last = p.getLong("camLastSeen", 0L)
            if (last == 0L || now - last < OFFLINE_MS || p.getBoolean("camOfflineAlerted", false)) continue
            p.edit().putBoolean("camOfflineAlerted", true).apply()
            Notif.alert(
                this, "Evdeki kamera çevrimdışı",
                "Son sinyal: ${fmtTime(last)}. Telefon kapanmış, interneti gitmiş ya da uygulama durmuş olabilir.",
                null, true
            )
        }
    }

    override fun onDestroy() {
        checker?.interrupt()
        checker = null
        running = false
        val c = conn
        if (c != null) Thread { try { c.disconnect() } catch (e: Exception) { } }.start()
        thread?.interrupt()
        thread = null
        super.onDestroy()
    }
}
