package com.bekci.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {

    private lateinit var codeEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var lastText: TextView
    private lateinit var lastImage: ImageView
    private var pending: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.channels(this)
        val p = Prefs.p(this)
        if (Prefs.code(this) == null) p.edit().putString("code", Pairing.newCode()).apply()

        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        fun label(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, px(14), 0, px(4))
        }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), px(40))
        }

        statusText = label("", 18f, true)
        col.addView(statusText)

        col.addView(label("Eşleştirme kodu", 17f, true))
        col.addView(label("İki telefonda da aynı kod olmalı. Bu telefondaki kodu diğer telefona aynen yaz.", 14f))
        codeEdit = EditText(this).apply {
            setText(p.getString("code", ""))
            textSize = 22f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        col.addView(codeEdit)
        col.addView(button("Yeni kod oluştur") { codeEdit.setText(Pairing.newCode()) })

        col.addView(label("Evde kalacak telefon", 17f, true))
        val sensLabel = label("", 14f)
        col.addView(sensLabel)
        val sens = SeekBar(this).apply {
            max = 9
            progress = p.getInt("sens", 6) - 1
        }
        fun showSens() {
            sensLabel.text = "Hassasiyet: ${sens.progress + 1} / 10. Boşuna uyarı gelirse düşür."
        }
        showSens()
        sens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                p.edit().putInt("sens", v + 1).apply()
                showSens()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(sens)
        col.addView(label("Başlattıktan sonra evden çıkman için 30 saniye var. Telefonu şarja tak. Ekran kapanabilir, izleme sürer.", 14f))
        col.addView(button("Bu telefon kamera olsun") { startCamera() })

        col.addView(label("Yanında taşıdığın telefon", 17f, true))
        col.addView(button("Bu telefon uyarı alsın") { startWatch() })
        lastText = label("", 14f)
        col.addView(lastText)
        lastImage = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
        }
        col.addView(lastImage)

        col.addView(label("Ayarlar", 17f, true))
        col.addView(label("İki telefonda da pil kısıtlamasını kaldır, yoksa Android uygulamayı bir süre sonra uyutabilir.", 14f))
        col.addView(button("Pil kısıtlamasını kaldır") { batteryExempt() })
        col.addView(button("Durdur") { stopAll() })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun saveCode(): String? {
        val n = Pairing.normalize(codeEdit.text.toString())
        if (n.length != 12) {
            toast("Kod 12 karakter olmalı, örneğin ABCD-EFGH-JKLM.")
            return null
        }
        val pretty = Pairing.pretty(n)
        codeEdit.setText(pretty)
        Prefs.p(this).edit().putString("code", pretty).apply()
        return pretty
    }

    private fun need(vararg perms: String): Array<String> =
        perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun notifPerm(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else arrayOf()

    private fun startCamera(asked: Boolean = false) {
        if (saveCode() == null) return
        if (!asked) {
            val missing = need(Manifest.permission.CAMERA, *notifPerm())
            if (missing.isNotEmpty()) {
                pending = { startCamera(true) }
                requestPermissions(missing, 1)
                return
            }
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("Kamera izni olmadan izleme yapılamaz.")
            return
        }
        stopService(Intent(this, WatchService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        toast("Kamera 30 saniye sonra izlemeye başlayacak.")
        statusText.postDelayed({ refresh() }, 600)
    }

    private fun startWatch(asked: Boolean = false) {
        if (saveCode() == null) return
        if (!asked) {
            val missing = need(*notifPerm())
            if (missing.isNotEmpty()) {
                pending = { startWatch(true) }
                requestPermissions(missing, 2)
                return
            }
        }
        if (need(*notifPerm()).isNotEmpty()) {
            toast("Bildirim izni verilmedi. Uyarılar görünmez; ayarlardan izin ver.")
        }
        stopService(Intent(this, CameraService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, WatchService::class.java))
        toast("Uyarılar açık. Kamera telefonu başlayınca bildirim gelecek.")
        statusText.postDelayed({ refresh() }, 600)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pending
        pending = null
        action?.invoke()
    }

    private fun stopAll() {
        stopService(Intent(this, CameraService::class.java))
        stopService(Intent(this, WatchService::class.java))
        statusText.postDelayed({ refresh() }, 600)
    }

    private fun batteryExempt() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Pil kısıtlaması zaten kaldırılmış.")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refresh() {
        statusText.text = when {
            CameraService.running -> "Bu telefon kamera olarak izliyor."
            WatchService.running -> "Bu telefon uyarı alıyor."
            else -> "Bekçi kapalı."
        }
        val t = Prefs.p(this).getLong("lastTime", 0L)
        lastText.text = when {
            t > 0 -> "Son hareket: ${fmtTime(t)}"
            WatchService.running -> "Henüz hareket yok."
            else -> ""
        }
        val f = File(filesDir, "last.jpg")
        if (t > 0 && f.exists()) {
            lastImage.setImageBitmap(BitmapFactory.decodeFile(f.path))
            lastImage.visibility = View.VISIBLE
        } else {
            lastImage.visibility = View.GONE
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
