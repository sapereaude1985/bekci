@file:OptIn(ExperimentalMaterial3Api::class)

package com.bekci.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.compose.material3.CircularProgressIndicator
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import org.json.JSONObject
import kotlin.math.roundToInt

private const val ROLE_CAM = "kamera"
private const val ROLE_WATCH = "izleyici"

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val p = remember { Prefs.p(ctx) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    fun say(s: String) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            snack.showSnackbar(s)
        }
    }

    var role by remember { mutableStateOf(p.getString("role", null)) }
    var code by remember { mutableStateOf(Prefs.code(ctx) ?: "") }
    var paired by remember { mutableStateOf(p.getBoolean("paired", false)) }
    var sens by remember { mutableFloatStateOf(p.getInt("sens", 6).toFloat()) }
    var shareAudio by remember { mutableStateOf(p.getBoolean("ses", true)) }
    var nightFlash by remember { mutableStateOf(p.getBoolean("geceFlas", true)) }
    var manualOpen by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val camOn = remember(tick) { CameraService.running }
    val watchOn = remember(tick) { WatchService.running }
    val batteryOk = remember(tick) {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val lastTime = remember(tick) { p.getLong("lastTime", 0L) }
    var motion by remember { mutableStateOf(p.getBoolean("hareket", true)) }
    LaunchedEffect(tick) { motion = p.getBoolean("hareket", true) }
    val camMotion = remember(tick) { p.getBoolean("camMotion", true) }
    val pendingSince = remember(tick) { p.getLong("camMotionPending", 0L) }
    val pendingAge = System.currentTimeMillis() - pendingSince
    var camSens by remember { mutableFloatStateOf(p.getInt("camSens", 6).toFloat()) }
    var sensDragging by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { if (!sensDragging) camSens = p.getInt("camSens", 6).toFloat() }
    val sensPending = remember(tick) { p.getLong("camSensPending", 0L) }
    val camHealth = remember(tick) { camHealth(p, WatchService.running) }
    val camFlash = remember(tick) { p.getBoolean("camFlash", true) }
    val flashPending = remember(tick) { p.getLong("camFlashPending", 0L) }

    var saveGallery by remember { mutableStateOf(p.getBoolean("galeri", true)) }
    var photos by remember { mutableStateOf<List<Pair<Uri, ImageBitmap>>>(emptyList()) }
    val photoKey = remember(tick) { p.getLong("photoSaved", 0L) }
    LaunchedEffect(photoKey, role) {
        if (role == ROLE_WATCH) photos = withContext(Dispatchers.IO) {
            Gallery.recent(ctx, 9).mapNotNull { u -> Gallery.thumb(ctx, u)?.let { u to it.asImageBitmap() } }
        }
    }
    fun openPhoto(u: Uri) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { say("Fotoğrafı açacak bir uygulama bulunamadı.") }
    }

    var fenceOn by remember { mutableStateOf(p.getBoolean("evHatirlat", false)) }
    var fenceBusy by remember { mutableStateOf(false) }
    var askHome by remember { mutableStateOf(false) }
    var askBg by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (fenceOn && p.getString("role", null) == ROLE_WATCH) HomeFence.register(ctx)
    }

    var afterPerms by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val a = afterPerms
        afterPerms = null
        a?.invoke()
    }
    fun withPerms(perms: List<String>, then: () -> Unit) {
        val missing = perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else {
            afterPerms = then
            permLauncher.launch(missing.toTypedArray())
        }
    }
    fun granted(perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    fun notifPerm() = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun startCam() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        perms += notifPerm()
        if (shareAudio) perms += Manifest.permission.RECORD_AUDIO
        withPerms(perms) {
            if (!granted(Manifest.permission.CAMERA)) {
                say("Kamera izni olmadan izleme yapılamaz.")
                return@withPerms
            }
            ctx.stopService(Intent(ctx, WatchService::class.java))
            ContextCompat.startForegroundService(ctx, Intent(ctx, CameraService::class.java))
            say(
                if (shareAudio && !granted(Manifest.permission.RECORD_AUDIO))
                    "Kamera 30 saniye içinde başlıyor. Mikrofon izni olmadığı için canlı yayında ses olmayacak."
                else "Kamera 30 saniye içinde izlemeye başlıyor."
            )
        }
    }
    fun startWatch() {
        withPerms(notifPerm()) {
            if (notifPerm().any { !granted(it) }) say("Bildirim izni verilmedi, uyarılar görünmeyecek.")
            ctx.stopService(Intent(ctx, CameraService::class.java))
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java).putExtra(WatchService.EXTRA_FRESH, true))
            say("Uyarılar açıldı.")
        }
    }
    fun restartIfRunning() {
        if (CameraService.running) {
            ctx.stopService(Intent(ctx, CameraService::class.java))
            ContextCompat.startForegroundService(ctx, Intent(ctx, CameraService::class.java))
        }
        if (WatchService.running) {
            ctx.stopService(Intent(ctx, WatchService::class.java))
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java).putExtra(WatchService.EXTRA_FRESH, true))
        }
    }
    fun applyCode(raw: String): Boolean {
        val n = Pairing.normalize(raw.trim().removePrefix(QR_PREFIX))
        if (n.length != 12) return false
        val pretty = Pairing.pretty(n)
        p.edit().putString("code", pretty).putBoolean("paired", true).remove("lastId").apply()
        code = pretty
        paired = true
        restartIfRunning()
        return true
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { r ->
        val raw = r.contents ?: return@rememberLauncherForActivityResult
        if (applyCode(raw)) say("Evdeki telefona bağlandı.") else say("Bu bir Bekçi QR kodu değil.")
    }
    fun scan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Evdeki telefonun ekranındaki QR kodu çerçeveye getir")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }
    fun setRemoteMotion(on: Boolean) {
        p.edit().putBoolean("camMotion", on).putLong("camMotionPending", System.currentTimeMillis()).apply()
        tick++
        Signal.send(
            Pairing.camInbox(code), code,
            JSONObject().put("t", "motion").put("sid", UUID.randomUUID().toString().take(8)).put("on", on)
        ) {
            p.edit().putBoolean("camMotion", !on).putLong("camMotionPending", 0L).apply()
            say("Kameraya iletilemedi. İnternet bağlantını kontrol et.")
        }
    }
    fun sendFlash(on: Boolean) {
        p.edit().putBoolean("camFlash", on).putLong("camFlashPending", System.currentTimeMillis()).apply()
        tick++
        Signal.send(
            Pairing.camInbox(code), code,
            JSONObject().put("t", "flash").put("sid", UUID.randomUUID().toString().take(8)).put("on", on)
        ) {
            p.edit().putBoolean("camFlash", !on).putLong("camFlashPending", 0L).apply()
            say("Kameraya iletilemedi. İnternet bağlantını kontrol et.")
        }
    }
    fun sendSens(v: Int) {
        p.edit().putInt("camSens", v).putLong("camSensPending", System.currentTimeMillis()).apply()
        Signal.send(
            Pairing.camInbox(code), code,
            JSONObject().put("t", "sens").put("sid", UUID.randomUUID().toString().take(8)).put("v", v)
        ) {
            p.edit().putLong("camSensPending", 0L).apply()
            say("Kameraya iletilemedi. İnternet bağlantını kontrol et.")
        }
    }
    fun captureAndRegister() {
        fenceBusy = true
        HomeFence.captureHome(ctx) { ok ->
            if (!ok) {
                fenceBusy = false
                say("Konum alınamadı. Telefonun konum ayarının açık olduğundan emin ol.")
            } else HomeFence.register(ctx) { reg ->
                fenceBusy = false
                if (reg) {
                    p.edit().putBoolean("evHatirlat", true).apply()
                    fenceOn = true
                    say("Ev konumu kaydedildi. Evden uzaklaşınca sorarım.")
                } else say("Konum takibi başlatılamadı. Konum açık mı, Google Play Hizmetleri var mı?")
            }
        }
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) captureAndRegister()
        else say("Konum izni “Her zaman izin ver” olmadan evden çıkış algılanamaz.")
    }
    fun startFence() {
        withPerms(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) + notifPerm()) {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                say("Kesin konum izni gerekiyor (yaklaşık konum yetmez).")
                return@withPerms
            }
            if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) askBg = true
            else captureAndRegister()
        }
    }
    fun stopFence() {
        HomeFence.unregister(ctx)
        p.edit().putBoolean("evHatirlat", false).apply()
        fenceOn = false
    }
    fun pinShortcut() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            say("Bu telefon kısayol eklemeyi desteklemiyor. Uygulama listesinde Bekçi’ye basılı tutup ana ekrana sürükle.")
            return
        }
        val info = ShortcutInfoCompat.Builder(ctx, "bekci-ana")
            .setShortLabel("Bekçi")
            .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .build()
        try { ShortcutManagerCompat.requestPinShortcut(ctx, info, null) } catch (e: Exception) {
            say("Kısayol eklenemedi.")
        }
    }
    fun pickRole(r: String?) {
        if (r == null) {
            if (fenceOn) stopFence()
            ctx.stopService(Intent(ctx, CameraService::class.java))
            ctx.stopService(Intent(ctx, WatchService::class.java))
            p.edit().remove("role").apply()
        } else p.edit().putString("role", r).apply()
        role = r
    }

    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header(role)
            when (role) {
                ROLE_CAM -> CameraHome(
                    camOn = camOn, code = code, sens = sens, shareAudio = shareAudio, batteryOk = batteryOk,
                    motion = motion,
                    nightFlash = nightFlash,
                    onNightFlash = { nightFlash = it; p.edit().putBoolean("geceFlas", it).apply() },
                    onMotion = {
                        motion = it
                        p.edit().putBoolean("hareket", it).apply()
                        if (camOn) say(if (it) "Hareket algılama 30 saniye sonra başlıyor." else "Hareket algılama kapatıldı.")
                    },
                    onToggle = { if (camOn) ctx.stopService(Intent(ctx, CameraService::class.java)) else startCam() },
                    onNewCode = { confirmNew = true },
                    onSens = { sens = it },
                    onSensDone = {
                        p.edit().putInt("sens", sens.roundToInt()).apply()
                        if (camOn) say("Hassasiyet ${sens.roundToInt()} olarak uygulandı.")
                    },
                    onAudio = {
                        shareAudio = it
                        p.edit().putBoolean("ses", it).apply()
                        if (camOn) say("Değişiklik, izlemeyi yeniden başlatınca geçerli olur.")
                    },
                    onBattery = { batteryExempt(ctx) },
                    onPin = { pinShortcut() },
                    onChangeRole = { pickRole(null) }
                )
                ROLE_WATCH -> WatcherHome(
                    paired = paired, watchOn = watchOn, code = code, lastTime = lastTime, batteryOk = batteryOk,
                    camMotion = camMotion,
                    camHealth = camHealth,
                    camFlash = camFlash,
                    flashStatus = when {
                        flashPending > 0 && System.currentTimeMillis() - flashPending < 30_000 -> "Evdeki kameraya iletiliyor…"
                        flashPending > 0 && !watchOn -> "İletildi. Onayı görmek için uyarıları aç."
                        flashPending > 0 -> "Kamera yanıt vermedi. Kamera telefonu kapalı olabilir."
                        camFlash -> "Açık. Karanlıkta hareket fotoğrafı çekilirken bir an yanar; canlı yayında 🔦 ile açabilirsin."
                        else -> "Kapalı. Flaş hiçbir durumda yanmaz."
                    },
                    onFlash = { sendFlash(it) },
                    motionStatus = when {
                        pendingSince > 0 && pendingAge < 30_000 -> "Evdeki kameraya iletiliyor…"
                        pendingSince > 0 && !watchOn -> "İletildi. Onayı görmek için uyarıları aç."
                        pendingSince > 0 -> "Kamera yanıt vermedi. Kamera telefonu kapalı olabilir."
                        camMotion -> "Açık. Evde hareket olunca bildirim gelir."
                        else -> "Kapalı. Kamera yalnızca canlı izleme için açık."
                    },
                    onMotion = { setRemoteMotion(it) },
                    camSens = camSens,
                    sensStatus = when {
                        sensPending > 0 && System.currentTimeMillis() - sensPending < 30_000 -> "Evdeki kameraya iletiliyor…"
                        sensPending > 0 && !watchOn -> "İletildi. Onayı görmek için uyarıları aç."
                        sensPending > 0 -> "Kamera yanıt vermedi. Kamera telefonu kapalı olabilir."
                        else -> "Kamera şu an bu ayarla çalışıyor."
                    },
                    onSens = { sensDragging = true; camSens = it },
                    onSensDone = { sensDragging = false; sendSens(camSens.roundToInt()) },
                    fenceOn = fenceOn, fenceBusy = fenceBusy,
                    onFence = { if (it) askHome = true else stopFence() },
                    onUpdateHome = { askHome = true },
                    onPin = { pinShortcut() },
                    photos = photos, onOpenPhoto = { openPhoto(it) },
                    saveGallery = saveGallery,
                    onSaveGallery = {
                        if (it && Build.VERSION.SDK_INT < 29 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                            withPerms(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                                val ok = granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                saveGallery = ok
                                p.edit().putBoolean("galeri", ok).apply()
                                if (!ok) say("Depolama izni olmadan galeriye kaydedilemez.")
                            }
                        } else {
                            saveGallery = it
                            p.edit().putBoolean("galeri", it).apply()
                        }
                    },
                    onScan = { scan() },
                    onManual = { manualOpen = true },
                    onLive = { ctx.startActivity(Intent(ctx, LiveActivity::class.java)) },
                    onToggle = { if (watchOn) ctx.stopService(Intent(ctx, WatchService::class.java)) else startWatch() },
                    onBattery = { batteryExempt(ctx) },
                    onChangeRole = { pickRole(null) }
                )
                else -> RolePicker { pickRole(it) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (manualOpen) {
        var text by remember { mutableStateOf("") }
        var err by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { manualOpen = false },
            title = { Text("Kodu elle gir") },
            text = {
                Column {
                    Text("Evdeki telefonda QR’ın altında yazan 12 karakterlik kodu yaz.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; err = false },
                        singleLine = true,
                        isError = err,
                        placeholder = { Text("ABCD-EFGH-JKLM") },
                        supportingText = { if (err) Text("Kod 12 karakter olmalı.") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false),
                        textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (applyCode(text)) { manualOpen = false; say("Evdeki telefona bağlandı.") } else err = true
                }) { Text("Bağlan") }
            },
            dismissButton = { TextButton(onClick = { manualOpen = false }) { Text("Vazgeç") } }
        )
    }

    if (askHome) {
        AlertDialog(
            onDismissRequest = { askHome = false },
            title = { Text("Şu an evde misin?") },
            text = { Text("Bulunduğun yer ev konumu olarak kaydedilecek. Evden yaklaşık ${HomeFence.RADIUS.toInt()} m uzaklaştığında, hareket algılama kapalıysa bildirim gelir. Bildirimdeki düğmeye basarsan açılır; basmazsan hiçbir şey değişmez.") },
            confirmButton = { TextButton(onClick = { askHome = false; startFence() }) { Text("Evdeyim, kaydet") } },
            dismissButton = { TextButton(onClick = { askHome = false }) { Text("Vazgeç") } }
        )
    }

    if (askBg) {
        AlertDialog(
            onDismissRequest = { askBg = false },
            title = { Text("Bir izin daha gerekiyor") },
            text = { Text("Telefon cebindeyken evden çıktığını anlayabilmem için konum iznini “Her zaman izin ver” yapman gerekiyor. Açılacak ekranda bunu seç. Konumun telefondan çıkmaz, yalnızca evden uzaklaşıp uzaklaşmadığına bakılır.") },
            confirmButton = {
                TextButton(onClick = {
                    askBg = false
                    if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Devam") }
            },
            dismissButton = { TextButton(onClick = { askBg = false }) { Text("Vazgeç") } }
        )
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text("Yeni kod oluşturulsun mu?") },
            text = { Text("İzleyici telefonun yeni QR kodu yeniden taraması gerekecek. Eski kodla bağlı telefon artık uyarı alamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    val n = Pairing.newCode()
                    p.edit().putString("code", n).remove("lastId").apply()
                    code = n
                    confirmNew = false
                    restartIfRunning()
                    say("Yeni kod oluşturuldu.")
                }) { Text("Oluştur") }
            },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("Vazgeç") } }
        )
    }
}

private fun batteryExempt(ctx: Context) {
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    } catch (e: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { }
    }
}

@Composable
private fun Header(role: String?) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(14.dp))
            Text("Bekçi", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when (role) {
                ROLE_CAM -> "Bu telefon: evdeki kamera"
                ROLE_WATCH -> "Bu telefon: izleyici"
                else -> "Eski telefonunu ev kamerasına çevir."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RolePicker(onPick: (String) -> Unit) {
    Text("Bu telefon ne olacak?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    RoleCard(
        Icons.Default.Home, "Evdeki kamera",
        "Evde kalacak telefon. Odayı görecek şekilde yerleştirip şarja takarsın."
    ) { onPick(ROLE_CAM) }
    RoleCard(
        Icons.Default.Notifications, "İzleyici",
        "Yanında taşıdığın telefon. Uyarılar buraya gelir, buradan canlı izlersin."
    ) { onPick(ROLE_WATCH) }
    Text(
        "İki telefona da Bekçi’yi kur; birinde “Evdeki kamera”yı, diğerinde “İzleyici”yi seç.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun RoleCard(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(c.primaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = c.onPrimaryContainer) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun PulseDot(on: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha"
    )
    Box(
        Modifier.size(12.dp).clip(CircleShape)
            .background(if (on) Color(0xFF2EB67D).copy(alpha = a) else MaterialTheme.colorScheme.outline)
    )
}

@Composable
private fun StatusCard(on: Boolean, title: String, desc: String, action: String, onAction: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (on) c.primaryContainer else c.surfaceVariant,
            contentColor = if (on) c.onPrimaryContainer else c.onSurfaceVariant
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(on)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (on) {
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(action) }
            } else {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(action) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun BatteryRow(ok: Boolean, onFix: () -> Unit) {
    SettingRow(
        "Pil kısıtlaması",
        if (ok) "Kaldırıldı, arka planda kesintisiz çalışır." else "Kaldırman önerilir, yoksa Android uygulamayı uyutabilir."
    ) {
        if (ok) Icon(Icons.Default.Check, contentDescription = "Tamam", tint = MaterialTheme.colorScheme.primary)
        else FilledTonalButton(onClick = onFix) { Text("Kaldır") }
    }
}

@Composable
private fun CameraHome(
    camOn: Boolean, code: String, sens: Float, shareAudio: Boolean, batteryOk: Boolean,
    motion: Boolean, onMotion: (Boolean) -> Unit,
    nightFlash: Boolean, onNightFlash: (Boolean) -> Unit,
    onToggle: () -> Unit, onNewCode: () -> Unit, onSens: (Float) -> Unit, onSensDone: () -> Unit,
    onAudio: (Boolean) -> Unit, onBattery: () -> Unit, onPin: () -> Unit, onChangeRole: () -> Unit
) {
    StatusCard(
        on = camOn,
        title = when {
            !camOn -> "Kamera kapalı"
            motion -> "İzliyor"
            else -> "Kamera açık"
        },
        desc = when {
            !camOn -> "Telefonu odayı görecek şekilde yerleştir ve şarja tak. Başlattıktan sonra evden çıkman için 30 saniyen var."
            motion -> "Hareket olursa izleyici telefona fotoğraflı uyarı gider. İzleyici istediğinde canlı yayın açılır."
            else -> "Hareket algılama kapalı, uyarı gönderilmez. İzleyici istediğinde canlı yayın yine açılır."
        },
        action = if (camOn) "Durdur" else "İzlemeyi başlat",
        onAction = onToggle
    )

    Section("İzleyici telefonu bağla") {
        Text(
            "Yanındaki telefonda Bekçi’yi aç, “İzleyici”yi seç ve bu QR kodu tarat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        val qr = remember(code) { qrBitmap(QR_PREFIX + Pairing.normalize(code), 512) }
        Box(
            Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(20.dp))
                .background(Color.White).padding(16.dp)
        ) {
            Image(qr.asImageBitmap(), contentDescription = "Eşleştirme QR kodu", modifier = Modifier.size(200.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            code,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        )
        TextButton(onClick = onNewCode, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Yeni kod oluştur") }
    }

    Section("Ayarlar") {
        SettingRow(
            "Hareket algılama",
            if (motion) "Hareket olunca izleyici telefona uyarı gider." else "Kapalı. Kamera yalnızca canlı izleme için açık kalır."
        ) { Switch(checked = motion, onCheckedChange = onMotion) }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SettingRow("Hassasiyet", "Kedi ya da perde yüzünden boşuna uyarı geliyorsa düşür.") {
            Text("${sens.roundToInt()} / 10", style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = sens, onValueChange = onSens, onValueChangeFinished = onSensDone,
            valueRange = 1f..10f, steps = 8
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SettingRow(
            "Flaş",
            if (nightFlash) "Açık. Karanlıkta hareket fotoğrafı çekilirken bir an yanar (izleyicide uyarılar açıksa); canlı yayında 🔦 ile açılabilir. Telefon kıpırdatılırsa yanmaz."
            else "Kapalı. Flaş hiçbir durumda yanmaz, canlı yayında da açılamaz."
        ) { Switch(checked = nightFlash, onCheckedChange = onNightFlash) }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SettingRow("Canlı yayında ortam sesi", "Mikrofon yalnızca biri canlı izlerken açılır.") {
            Switch(checked = shareAudio, onCheckedChange = onAudio)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        BatteryRow(batteryOk, onBattery)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        PinRow(onPin)
    }

    Text(
        "Canlı yayın sırasında hareket algılama kısa süre duraklar, yayın bitince kendiliğinden devam eder. Biri canlı izlerken bu telefonun bildiriminde “Canlı izleniyor” yazar.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    TextButton(onClick = onChangeRole) { Text("Bu telefonun görevini değiştir") }
}

@Composable
private fun WatcherHome(
    paired: Boolean, watchOn: Boolean, code: String, lastTime: Long, batteryOk: Boolean,
    camMotion: Boolean, motionStatus: String, onMotion: (Boolean) -> Unit,
    camHealth: CamHealth,
    camFlash: Boolean, flashStatus: String, onFlash: (Boolean) -> Unit,
    camSens: Float, sensStatus: String, onSens: (Float) -> Unit, onSensDone: () -> Unit,
    fenceOn: Boolean, fenceBusy: Boolean, onFence: (Boolean) -> Unit, onUpdateHome: () -> Unit, onPin: () -> Unit,
    photos: List<Pair<Uri, ImageBitmap>>, onOpenPhoto: (Uri) -> Unit,
    saveGallery: Boolean, onSaveGallery: (Boolean) -> Unit,
    onScan: () -> Unit, onManual: () -> Unit, onLive: () -> Unit, onToggle: () -> Unit,
    onBattery: () -> Unit, onChangeRole: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    if (!paired) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("Evdeki telefona bağlan", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("Evdeki telefonda Bekçi’yi açıp “Evdeki kamera”yı seç. Ekrana gelen QR kodu bu telefonla tara.")
                Spacer(Modifier.height(18.dp))
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("QR kodu tara") }
                TextButton(onClick = onManual, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Kodu elle gir") }
            }
        }
        TextButton(onClick = onChangeRole) { Text("Bu telefonun görevini değiştir") }
        return
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.linearGradient(listOf(c.primary, c.tertiary)))
                .padding(24.dp)
        ) {
            Text("Canlı izle", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c.onPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "Evdeki kameranın görüntüsünü ve ortam sesini anında aç.",
                style = MaterialTheme.typography.bodyMedium, color = c.onPrimary.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onLive,
                modifier = Modifier.height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.onPrimary, contentColor = c.primary)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Canlı yayını aç", fontWeight = FontWeight.SemiBold)
            }
        }
    }

    HealthCard(camHealth)

    StatusCard(
        on = watchOn,
        title = if (watchOn) "Uyarılar açık" else "Uyarılar kapalı",
        desc = if (watchOn) "Evde hareket olunca fotoğraflı bildirim gelir." else "Aç ki evde hareket olunca bildirim gelsin.",
        action = if (watchOn) "Kapat" else "Uyarıları aç",
        onAction = onToggle
    )

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hareket algılama", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(motionStatus, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = camMotion, onCheckedChange = onMotion)
        }
        if (camMotion) {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hassasiyet", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${camSens.roundToInt()} / 10", style = MaterialTheme.typography.titleMedium)
                }
                Slider(value = camSens, onValueChange = onSens, onValueChangeFinished = onSensDone, valueRange = 1f..10f, steps = 8)
                Text(
                    "Az ← boşuna uyarı geliyorsa düşür · hareket kaçıyorsa yükselt → Çok",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(sensStatus, style = MaterialTheme.typography.bodySmall, color = c.primary)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Flaş", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(flashStatus, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = camFlash, onCheckedChange = onFlash)
        }
    }

    Section("Evden çıkınca hatırlat") {
        SettingRow(
            if (fenceOn) "Açık" else "Kapalı",
            if (fenceOn) "Evden yaklaşık ${HomeFence.RADIUS.toInt()} m uzaklaşınca, hareket algılama kapalıysa açmak isteyip istemediğin sorulur."
            else "Evden uzaklaştığında hareket algılamayı açmayı hatırlatır. Ayarlamak için evde olman gerekir."
        ) {
            if (fenceBusy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            else Switch(checked = fenceOn, onCheckedChange = onFence)
        }
        if (fenceOn) TextButton(onClick = onUpdateHome) { Text("Ev konumunu buraya güncelle") }
    }

    Section("Son hareket") {
        if (lastTime > 0) {
            val ctx = LocalContext.current
            val bmp = remember(lastTime) {
                val f = File(ctx.filesDir, "last.jpg")
                if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            }
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), contentDescription = "Son hareket anındaki görüntü",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.FillWidth
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(fmtTime(lastTime), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text("Henüz hareket yok.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }

    Section("Fotoğraflar") {
        Text(
            "Galeride “Bekçi” klasöründe: hareket fotoğrafları “Hareket”, canlı yayından kaydettiklerin “Canlı” alt klasöründe.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (photos.isEmpty()) {
            Text("Henüz kaydedilmiş fotoğraf yok.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        } else {
            photos.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (uri, img) ->
                        Image(
                            img, contentDescription = "Kaydedilen fotoğraf",
                            modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenPhoto(uri) },
                            contentScale = ContentScale.Crop
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SettingRow(
            "Hareket fotoğraflarını kaydet",
            "Google Fotoğraflar yedeklemesi açıksa bu klasörü de buluta yükleyebilir."
        ) { Switch(checked = saveGallery, onCheckedChange = onSaveGallery) }
    }

    Section("Ayarlar") {
        SettingRow("Eşleştirme", code) { TextButton(onClick = onScan) { Text("Yeniden tara") } }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        BatteryRow(batteryOk, onBattery)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        PinRow(onPin)
    }
    TextButton(onClick = onChangeRole) { Text("Bu telefonun görevini değiştir") }
}

@Composable
private fun PinRow(onPin: () -> Unit) {
    SettingRow("Ana ekrana ekle", "Bekçi simgesini ana ekrana koyar.") {
        FilledTonalButton(onClick = onPin) { Text("Ekle") }
    }
}

data class CamHealth(val level: Int, val title: String, val detail: String) // level: 0 gri, 1 yeşil, 2 kırmızı, 3 sarı

private fun ago(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0)
    return when {
        m < 1 -> "az önce"
        m < 60 -> "$m dk önce"
        m < 48 * 60 -> "${m / 60} sa önce"
        else -> "${m / 1440} gün önce"
    }
}

private fun camHealth(p: android.content.SharedPreferences, watchOn: Boolean): CamHealth {
    if (!watchOn) return CamHealth(0, "Durum bilinmiyor", "Evdeki kameranın durumunu görmek için uyarıları aç.")
    if (!p.getBoolean("camHbSeen", false))
        return CamHealth(0, "Durum bilgisi bekleniyor", "Evdeki telefonda Bekçi 2.8 veya sonrası çalışınca birkaç dakika içinde görünür.")
    val last = p.getLong("camLastSeen", 0L)
    val gap = System.currentTimeMillis() - last
    if (!p.getBoolean("camRunning", false)) return CamHealth(0, "Kamera kapalı", "Durduruldu · ${ago(gap)}")
    val pct = p.getInt("camBattery", -1)
    val chg = p.getBoolean("camCharging", false)
    val bat = if (pct >= 0) " · Pil %$pct" + (if (chg) ", şarja takılı" else ", şarja takılı değil") else ""
    return when {
        gap > WatchService.OFFLINE_MS -> CamHealth(2, "Çevrimdışı", "Son sinyal ${ago(gap)}$bat")
        pct in 0..20 && !chg -> CamHealth(3, "Çevrimiçi, pil azalıyor", "Son sinyal ${ago(gap)}$bat")
        else -> CamHealth(1, "Çevrimiçi", "Son sinyal ${ago(gap)}$bat")
    }
}

@Composable
private fun HealthCard(h: CamHealth) {
    val c = MaterialTheme.colorScheme
    val dot = when (h.level) {
        1 -> Color(0xFF2EB67D)
        2 -> Color(0xFFE5484D)
        3 -> Color(0xFFF5A524)
        else -> c.outline
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = if (h.level == 2) CardDefaults.cardColors(containerColor = c.errorContainer, contentColor = c.onErrorContainer)
        else CardDefaults.cardColors()
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Evdeki kamera: ${h.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(h.detail, style = MaterialTheme.typography.bodyMedium, color = if (h.level == 2) c.onErrorContainer else c.onSurfaceVariant)
            }
        }
    }
}
