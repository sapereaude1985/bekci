package com.bekci.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Prefs {
    fun p(c: Context): SharedPreferences = c.getSharedPreferences("bekci", Context.MODE_PRIVATE)
    fun code(c: Context): String? = p(c).getString("code", null)
}

/**
 * Pairing code -> private ntfy topic + AES key.
 * Images are encrypted on the camera phone and decrypted on the watching phone,
 * so the ntfy server only ever sees unreadable bytes.
 */
object Pairing {
    private const val ALPH = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun newCode(): String {
        val r = SecureRandom()
        val s = (1..12).map { ALPH[r.nextInt(ALPH.length)] }.joinToString("")
        return pretty(s)
    }

    // Locale.ROOT matters: in a Turkish locale "i".uppercase() would give "İ".
    fun normalize(c: String): String = c.uppercase(Locale.ROOT).filter { it in ALPH }

    fun pretty(c: String): String = normalize(c).chunked(4).joinToString("-")

    private fun sha(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    fun camInbox(code: String): String = topic(code) + "_k"
    fun watchInbox(code: String): String = topic(code) + "_i"

    fun topic(code: String): String =
        "bekci_" + sha("topic:" + normalize(code)).take(16).joinToString("") { "%02x".format(it) }

    private fun key(code: String) = SecretKeySpec(sha("key:" + normalize(code)), "AES")

    fun encrypt(code: String, data: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(code), GCMParameterSpec(128, iv))
        return iv + c.doFinal(data)
    }

    fun decrypt(code: String, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(code), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** Minimal ntfy.sh client using only the platform HTTP stack. */
object Ntfy {
    const val HOST = "https://ntfy.sh/"

    fun postText(topic: String, text: String) {
        val c = URL(HOST + topic).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 30000
            val b = text.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }

    fun putFile(topic: String, data: ByteArray) {
        val c = URL(HOST + topic).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("Filename", "h.bin")
            c.setRequestProperty("Message", "h")
            c.setFixedLengthStreamingMode(data.size)
            c.outputStream.use { it.write(data) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, max: Int = 8_000_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 30000
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            c.inputStream.use { inp ->
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IOException("too big")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }
}

object Notif {
    const val CH_STATUS = "durum"
    const val CH_ALERT = "hareket"
    private var nextId = 100

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Çalışma durumu", NotificationManager.IMPORTANCE_LOW)
        )
        val alert = NotificationChannel(CH_ALERT, "Hareket uyarıları", NotificationManager.IMPORTANCE_HIGH)
        alert.enableVibration(true)
        alert.vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 400)
        nm.createNotificationChannel(alert)
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun status(ctx: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_bekci)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .build()

    @Synchronized
    fun alert(ctx: Context, title: String, text: String, bmp: Bitmap?, important: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val b = NotificationCompat.Builder(ctx, if (important) CH_ALERT else CH_STATUS)
            .setSmallIcon(if (important) R.drawable.ic_stat_bekci else R.drawable.ic_stat_bekci)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(0xFF2F54D6.toInt())
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .setPriority(if (important) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
        if (important) b.setCategory(NotificationCompat.CATEGORY_ALARM)
        if (bmp != null) {
            b.setLargeIcon(bmp)
            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp))
        }
        ctx.getSystemService(NotificationManager::class.java).notify(nextId++, b.build())
    }
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("d MMMM HH:mm:ss", Locale("tr", "TR")).format(Date(ms))

const val QR_PREFIX = "bekci:"

fun qrBitmap(text: String, size: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 0,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
    )
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        px[y * size + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
