package com.bekci.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.IntentFilter
import android.os.BatteryManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import org.json.JSONObject
import java.util.Locale
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * Runs on the phone left at home. Keeps the back camera open as a foreground
 * service (works with the screen off), compares low-res frames, and on motion
 * sends an encrypted photo to the paired phone via ntfy.
 */
class CameraService : LifecycleService() {

    companion object {
        @Volatile var running = false
        // Share of the frame that must change, by sensitivity 1..10.
        private val THR = floatArrayOf(0.12f, 0.09f, 0.07f, 0.05f, 0.04f, 0.03f, 0.022f, 0.016f, 0.011f, 0.007f)
        private const val GW = 80
        private const val GH = 60
        private const val COOLDOWN = 30_000L
        private const val ARM_DELAY = 30_000L
        private const val HB_EVERY = 5 * 60_000L
    }

    private val analysisExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val netExec: ExecutorService = Executors.newSingleThreadExecutor()
    private var started = false
    private var prev: IntArray? = null
    private var hits = 0
    private var lastEvent = 0L
    private var lastFrame = 0L
    private var armedAt = 0L
    @Volatile private var threshold = 0.03f
    private var code = ""
    private var topic = ""
    private var wake: PowerManager.WakeLock? = null

    private val main = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var sig: SignalChannel? = null
    private var live: CamLive? = null
    private var liveSid = ""
    private var micOk = false
    @Volatile private var motionOn = true

    // ---- torch / night ----
    @Volatile private var camera: Camera? = null
    @Volatile private var torchOn = false
    @Volatile private var torchAutoUntil = 0L
    @Volatile private var settleUntil = 0L
    @Volatile private var dark = false
    private var flashShotAt = 0L

    // The phone itself being moved/bumped must not fire the flash.
    @Volatile private var deviceMovedAt = 0L
    // Room climate: only some phones have these sensors.
    @Volatile private var roomTemp = Float.NaN
    @Volatile private var roomHum = Float.NaN
    private val climate = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_AMBIENT_TEMPERATURE -> roomTemp = e.values[0]
                Sensor.TYPE_RELATIVE_HUMIDITY -> roomHum = e.values[0]
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    private val grav = FloatArray(3)
    private var gravInit = false
    private val shake = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (!gravInit) { System.arraycopy(e.values, 0, grav, 0, 3); gravInit = true; return }
            var d = 0f
            for (i in 0..2) {
                grav[i] = 0.9f * grav[i] + 0.1f * e.values[i]
                val lin = e.values[i] - grav[i]
                d += lin * lin
            }
            if (d > 1.0f) deviceMovedAt = System.currentTimeMillis()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    private var liveStart = 0L
    private var liveLumaChecked = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            sendHeartbeat()
            main.postDelayed(this, HB_EVERY)
        }
    }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == "hareket") main.post { applyMotion(sp.getBoolean("hareket", true)) }
        if (key == "sens") main.post { applySens(sp.getInt("sens", 6)) }
        if (key == "geceFlas") main.post { applyFlashSetting(sp.getBoolean("geceFlas", true)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notif.channels(this)
        if (!started) motionOn = Prefs.p(this).getBoolean("hareket", true)
        val n = statusNotif(false)
        if (!started) {
            micOk = Prefs.p(this).getBoolean("ses", true) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (micOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                try {
                    startForeground(1, n, type)
                } catch (e: Exception) {
                    micOk = false
                    startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                }
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        val c = Prefs.code(this)
        if (c == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            running = true
            code = c
            topic = Pairing.topic(c)
            val sens = Prefs.p(this).getInt("sens", 6)
            threshold = THR[(sens - 1).coerceIn(0, 9)]
            armedAt = System.currentTimeMillis() + ARM_DELAY
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bekci:kamera").apply {
                acquire(24 * 60 * 60 * 1000L)
            }
            Prefs.p(this).registerOnSharedPreferenceChangeListener(prefListener)
            try {
                val f = IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                }
                ContextCompat.registerReceiver(this, power, f, ContextCompat.RECEIVER_NOT_EXPORTED)
                powerRegistered = true
            } catch (e: Exception) { }
            try {
                val sm = getSystemService(SensorManager::class.java)
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(shake, it, SensorManager.SENSOR_DELAY_NORMAL) }
                sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let { sm.registerListener(climate, it, SensorManager.SENSOR_DELAY_NORMAL) }
                sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)?.let { sm.registerListener(climate, it, SensorManager.SENSOR_DELAY_NORMAL) }
            } catch (e: Exception) { }
            startCamera()
            sig = SignalChannel(Pairing.camInbox(c), c, onOpen = {}, onMsg = { m -> main.post { onSignal(m) } })
                .also { it.start() }
            val t = topic
            netExec.execute {
                try { Ntfy.postText(t, "start") } catch (e: Exception) { }
                if (!motionOn) try { Ntfy.postText(t, "motion_off") } catch (e: Exception) { }
                try { Ntfy.postText(t, "sens_" + Prefs.p(this).getInt("sens", 6)) } catch (e: Exception) { }
                try { Ntfy.postText(t, "flash_" + (if (flashAllowed()) 1 else 0)) } catch (e: Exception) { }
                main.postDelayed(heartbeat, 2000L)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (!running || live != null) return@addListener
                val provider = future.get()
                this.provider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExec) { img ->
                    try { analyze(img) } catch (e: Exception) { } finally { img.close() }
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                if (torchOn) camera?.cameraControl?.enableTorch(true)
            } catch (e: Exception) {
                Notif.alert(this, "Kamera açılamadı", e.message ?: "Bilinmeyen hata", null, false)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        if (!motionOn) { prev = null; hits = 0; return }
        val now = System.currentTimeMillis()
        if (now - lastFrame < 250) return
        lastFrame = now

        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = img.width
        val h = img.height
        val cur = IntArray(GW * GH)
        for (y in 0 until GH) {
            val row = (y * h / GH) * rs
            for (x in 0 until GW) {
                val i = row + (x * w / GW) * ps
                val r = buf.get(i).toInt() and 0xff
                val g = buf.get(i + 1).toInt() and 0xff
                val b = buf.get(i + 2).toInt() and 0xff
                cur[y * GW + x] = (r * 77 + g * 150 + b * 29) shr 8
            }
        }
        var sum = 0
        for (v in cur) sum += v
        val avg = sum / cur.size
        if (!torchOn) { if (avg < 40) dark = true else if (avg > 60) dark = false }

        if (flashShotAt > 0 && now >= flashShotAt) {
            flashShotAt = 0
            prev = cur
            val bmp = img.toBitmap()
            val rot = img.imageInfo.rotationDegrees
            main.post { if (live == null && torchOn) setTorch(false) } // photo taken: flash off
            netExec.execute { sendAlert(bmp, rot) }
            return
        }
        val until = torchAutoUntil
        if (torchOn && until > 0 && now > until && flashShotAt == 0L) {
            main.post { if (torchAutoUntil in 1..System.currentTimeMillis()) setTorch(false) }
        }

        val old = prev
        prev = cur
        if (old == null || now < armedAt || now < settleUntil) return

        var changed = 0
        for (i in cur.indices) if (abs(cur[i] - old[i]) > 28) changed++
        val frac = changed.toFloat() / cur.size

        if (frac > threshold) {
            hits++
            if (hits >= 2 && now - lastEvent > COOLDOWN) {
                hits = 0
                lastEvent = now
                val phoneMoving = now - deviceMovedAt < 2500L
                if (dark && !torchOn && !phoneMoving && live == null && nightFlash() && hasFlash()) {
                    // Night: flash on, let exposure settle ~1 s, take the photo, flash off.
                    flashShotAt = now + 1200L
                    main.post { setTorch(true) }
                } else {
                    val bmp = img.toBitmap()
                    val rot = img.imageInfo.rotationDegrees
                    netExec.execute { sendAlert(bmp, rot) }
                }
            }
        } else {
            hits = 0
        }
    }

    private fun sendAlert(src: Bitmap, rot: Int) {
        var jpeg: ByteArray? = null
        try {
            val scale = 800f / max(src.width, src.height)
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            m.postRotate(rot.toFloat())
            val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            jpeg = out.toByteArray()
        } catch (e: Exception) { }

        for (attempt in 1..3) {
            try {
                val data = jpeg
                if (data != null) Ntfy.putFile(topic, Pairing.encrypt(code, data))
                else Ntfy.postText(topic, "h")
                return
            } catch (e: Exception) {
                try { Thread.sleep(3000L * attempt) } catch (ie: InterruptedException) { return }
            }
        }
        // Photo could not be sent; at least deliver the alert itself.
        try { Ntfy.postText(topic, "h") } catch (e: Exception) { }
    }

    // ---------- live view ----------

    private fun onSignal(m: JSONObject) {
        if (!running) return
        val sid = m.optString("sid")
        if (sid.isEmpty()) return
        when (m.optString("t")) {
            "call" -> beginLive(sid, m.optBoolean("ses", true) && micOk)
            "answer" -> if (sid == liveSid) live?.onAnswer(m.optString("sdp"))
            "bye" -> if (sid == liveSid) live?.end("izleyici kapattı")
            "torch" -> if (sid == liveSid) setTorch(m.optBoolean("on", false))
            "flash" -> {
                val on = m.optBoolean("on", true)
                val p = Prefs.p(this)
                if (p.getBoolean("geceFlas", true) == on) announceFlash(on)
                else p.edit().putBoolean("geceFlas", on).apply()
            }
            "alerts" -> {
                val on = m.optBoolean("on", true)
                Prefs.p(this).edit().putBoolean("izleyiciUyari", on).apply()
                if (!on) torchOffUnlessLive() else sendStatus()
            }
            "status" -> sendStatus()
            "sens" -> {
                val v = m.optInt("v", 6).coerceIn(1, 10)
                val p = Prefs.p(this)
                if (p.getInt("sens", 6) == v) announceSens(v)
                else p.edit().putInt("sens", v).apply()
            }
            "motion" -> {
                val on = m.optBoolean("on", true)
                val p = Prefs.p(this)
                if (p.getBoolean("hareket", true) == on) announceMotion(on)
                else p.edit().putBoolean("hareket", on).apply()
            }
        }
    }

    private fun beginLive(sid: String, audio: Boolean) {
        if (sid == liveSid) return
        live?.end("yeni istek")
        liveSid = sid
        try { provider?.unbindAll() } catch (e: Exception) { }
        prev = null
        hits = 0
        showNotif(true)
        val l = CamLive(
            this, sid, audio,
            sendSig = { j -> Signal.send(Pairing.watchInbox(code), code, j) },
            onEnd = { reason -> main.post { liveEnded(sid, reason) } }
        )
        live = l
        l.start()
        bindLive(l)
    }

    private fun liveEnded(sid: String, reason: String) {
        if (sid != liveSid) return
        live = null
        liveSid = ""
        torchOn = false
        torchAutoUntil = 0L
        flashShotAt = 0L
        if (reason != "izleyici kapattı") {
            Signal.send(Pairing.watchInbox(code), code, JSONObject().put("t", "bye").put("sid", sid).put("msg", reason))
        }
        if (!running) return
        showNotif(false)
        prev = null
        armedAt = max(armedAt, System.currentTimeMillis() + 5000L)
        main.postDelayed({ if (running && live == null) startCamera() }, 600)
    }

    private fun statusNotif(isLive: Boolean) = when {
        isLive -> Notif.status(this, "Canlı izleniyor", "Diğer telefon şu an kamerayı izliyor.")
        motionOn -> Notif.status(this, "Kamera izliyor", "Hareket olursa diğer telefona haber verilecek.")
        else -> Notif.status(this, "Kamera açık", "Hareket algılama kapalı. Canlı izleme kullanılabilir.")
    }

    /** "_<room °C>_<humidity %>_<phone/battery °C>", x = no sensor. Also kept locally for this phone's screen. */
    private fun climateSuffix(): String {
        var bt = Float.NaN
        try {
            val st = ContextCompat.registerReceiver(
                this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            val t = st?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (t != Int.MIN_VALUE) bt = t / 10f
        } catch (e: Exception) { }
        fun f(v: Float) = if (v.isNaN()) "x" else String.format(Locale.ROOT, "%.1f", v)
        val s = "_${f(roomTemp)}_${f(roomHum)}_${f(bt)}"
        Prefs.p(this).edit().putString("yerelIklim", s).putLong("yerelIklimAt", System.currentTimeMillis()).apply()
        return s
    }

    /** Full, silent status for the watcher: st_<battery>_<plugged>_<motion>_<sens>_<flash>. */
    private fun sendStatus() {
        val t = topic
        if (t.isEmpty()) return
        val (pct, chg) = batteryState()
        val msg = "st_${pct}_${if (chg) 1 else 0}_${if (motionOn) 1 else 0}_" +
            "${Prefs.p(this).getInt("sens", 6)}_${if (flashAllowed()) 1 else 0}" + climateSuffix()
        netExec.execute { try { Ntfy.postText(t, msg) } catch (e: Exception) { } }
    }

    /** "hb_<battery %>_<1 if charging>": lets the watcher notice when this phone goes quiet. */
    private fun sendHeartbeat() {
        val t = topic
        if (t.isEmpty()) return
        val (pct, charging) = batteryState()
        val msg = "hb_${pct}_${if (charging) 1 else 0}" + climateSuffix()
        netExec.execute { try { Ntfy.postText(t, msg) } catch (e: Exception) { } }
    }

    /** Motion photos may use the flash only while detection is on and the watcher actually gets alerts. */
    /**
     * Battery % and "on the charger". Read three independent ways, because one of them fails
     * silently on some phones, and phones with battery protection (charging held at 80-85 %)
     * report "not charging" even while plugged in — that still counts as on the charger here.
     */
    private fun batteryState(): Pair<Int, Boolean> {
        var pct = -1
        var plugged = false
        val bm = try { getSystemService(BatteryManager::class.java) } catch (e: Exception) { null }
        try { pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 } catch (e: Exception) { }
        try {
            val st = ContextCompat.registerReceiver(
                this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            if (st != null) {
                if (st.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) plugged = true
                val s = st.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL) plugged = true
                if (pct < 0) {
                    val lvl = st.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val sc = st.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (lvl >= 0 && sc > 0) pct = lvl * 100 / sc
                }
            }
        } catch (e: Exception) { }
        try { if (bm?.isCharging == true) plugged = true } catch (e: Exception) { }
        try {
            val s = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
            if (s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL ||
                s == BatteryManager.BATTERY_STATUS_NOT_CHARGING) plugged = true
        } catch (e: Exception) { }
        return pct to plugged
    }

    /** Cable in/out: report right away instead of waiting for the next 5-minute heartbeat. */
    private val power = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            if (running) main.postDelayed({ sendHeartbeat() }, 1500L)
        }
    }
    private var powerRegistered = false

    private fun nightFlash(): Boolean {
        val p = Prefs.p(this)
        return p.getBoolean("geceFlas", true) && motionOn && p.getBoolean("izleyiciUyari", true)
    }

    /** Outside live view the torch may stay on only for a motion photo. */
    private fun torchOffUnlessLive() {
        flashShotAt = 0L
        if (live == null && torchOn) setTorch(false)
    }

    private fun hasFlash() = camera?.cameraInfo?.hasFlashUnit() == true

    private fun flashAllowed() = Prefs.p(this).getBoolean("geceFlas", true)

    private fun applyFlashSetting(on: Boolean) {
        if (!running) return
        if (!on) {
            flashShotAt = 0L
            if (torchOn) setTorch(false)
        }
        if (live != null) sendTorchState(torchOn)
        announceFlash(on)
    }

    private fun announceFlash(on: Boolean) {
        val t = topic
        if (t.isEmpty()) return
        netExec.execute { try { Ntfy.postText(t, "flash_" + (if (on) 1 else 0)) } catch (e: Exception) { } }
    }

    /** Main thread. */
    private fun setTorch(on: Boolean) {
        val cam = camera
        if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
            if (live != null) sendTorchState(false, missing = true)
            return
        }
        if (on && !flashAllowed()) {
            if (live != null) sendTorchState(false)
            return
        }
        try { cam.cameraControl.enableTorch(on) } catch (e: Exception) { }
        torchOn = on
        if (!on) torchAutoUntil = 0L
        settleUntil = System.currentTimeMillis() + 1500L
        prev = null
        hits = 0
        if (live != null) sendTorchState(on)
    }

    private fun sendTorchState(on: Boolean, missing: Boolean = false) {
        val sid = liveSid
        if (sid.isEmpty()) return
        Signal.send(
            Pairing.watchInbox(code), code,
            JSONObject().put("t", "torch").put("sid", sid).put("on", on).put("yok", missing).put("izin", flashAllowed())
        )
    }

    private fun bindLive(l: CamLive) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (live !== l) return@addListener
            try {
                val pr = future.get()
                provider = pr
                val sel = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                val a = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setResolutionSelector(sel)
                    .build()
                liveStart = System.currentTimeMillis()
                liveLumaChecked = false
                a.setAnalyzer(analysisExec) { img ->
                    try { if (live === l) liveFrame(img, l) } catch (e: Exception) { } finally { img.close() }
                }
                pr.unbindAll()
                val cam = pr.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, a)
                camera = cam
                if (torchOn) cam.cameraControl.enableTorch(true)
                sendTorchState(torchOn, missing = !cam.cameraInfo.hasFlashUnit())
            } catch (e: Exception) {
                l.end("kamera açılamadı")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun liveFrame(img: ImageProxy, l: CamLive) {
        l.pushFrame(img)
        if (!liveLumaChecked && System.currentTimeMillis() - liveStart > 1500L) {
            liveLumaChecked = true
            if (!torchOn && Prefs.p(this).getBoolean("geceFlas", true) && yAverage(img) < 40) {
                main.post { if (live === l && !torchOn) setTorch(true) }
            }
        }
    }

    private fun yAverage(img: ImageProxy): Int {
        val pl = img.planes[0]
        val b = pl.buffer
        val rs = pl.rowStride
        val w = img.width
        val h = img.height
        var sum = 0L
        var n = 0
        for (y in 0 until 30) {
            val row = (y * h / 30) * rs
            for (x in 0 until 40) {
                val i = row + (x * w / 40) * pl.pixelStride
                if (i < b.limit()) { sum += b.get(i).toInt() and 0xff; n++ }
            }
        }
        return if (n == 0) 255 else (sum / n).toInt()
    }

    private fun showNotif(isLive: Boolean) {
        try { getSystemService(NotificationManager::class.java).notify(1, statusNotif(isLive)) } catch (e: Exception) { }
    }

    private fun applyMotion(on: Boolean) {
        if (!running || on == motionOn) return
        motionOn = on
        prev = null
        hits = 0
        if (on) armedAt = System.currentTimeMillis() + ARM_DELAY else torchOffUnlessLive()
        showNotif(live != null)
        announceMotion(on)
    }

    /** Applied immediately, no restart needed. */
    private fun applySens(v: Int) {
        if (!running) return
        threshold = THR[(v - 1).coerceIn(0, 9)]
        prev = null
        hits = 0
        announceSens(v)
    }

    private fun announceSens(v: Int) {
        val t = topic
        if (t.isEmpty()) return
        netExec.execute { try { Ntfy.postText(t, "sens_$v") } catch (e: Exception) { } }
    }

    private fun announceMotion(on: Boolean) {
        val t = topic
        if (t.isEmpty()) return
        netExec.execute { try { Ntfy.postText(t, if (on) "motion_on" else "motion_off") } catch (e: Exception) { } }
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(heartbeat)
        if (powerRegistered) { try { unregisterReceiver(power) } catch (e: Exception) { }; powerRegistered = false }
        try {
            val sm = getSystemService(SensorManager::class.java)
            sm.unregisterListener(shake)
            sm.unregisterListener(climate)
        } catch (e: Exception) { }
        Prefs.p(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        sig?.stop()
        sig = null
        live?.end("kamera kapandı")
        live = null
        val t = topic
        if (t.isNotEmpty()) Thread { try { Ntfy.postText(t, "stop") } catch (e: Exception) { } }.start()
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        super.onDestroy()
        analysisExec.shutdown()
        netExec.shutdown()
    }
}
