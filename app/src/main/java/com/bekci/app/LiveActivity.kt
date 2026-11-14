package com.bekci.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.AudioSource
import org.webrtc.MediaStreamTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.MediaStreamTrack as RtcTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class LiveActivity : ComponentActivity() {

    enum class Phase { Connecting, Live, Failed }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var renderer: SurfaceViewRenderer
    private var chan: SignalChannel? = null
    private var pc: PeerConnection? = null
    private var remoteAudio: AudioTrack? = null
    private var remoteVideo: VideoTrack? = null
    private var recorder: LiveRecorder? = null
    private val recording = mutableStateOf(false)
    private val recSince = mutableLongStateOf(0L)
    private val recMax = Runnable { stopRecording("10 dakika doldu, kayıt otomatik durdu.") }
    private var sid = ""
    private var code = ""
    private var answered = false
    private var answerSent = false
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false

    private val phase = mutableStateOf(Phase.Connecting)
    private val message = mutableStateOf("Kameraya bağlanılıyor…")
    private val muted = mutableStateOf(false)
    private val hasAudio = mutableStateOf(false)
    private val liveSince = mutableLongStateOf(0L)
    private val canTalk = mutableStateOf(false)
    private val torchAvail = mutableStateOf(false)
    private val torchOn = mutableStateOf(false)
    private var torchAllowed = true
    private val talking = mutableStateOf(false)
    private var micSrc: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { connect() }

    private val offerTimeout = Runnable {
        fail("Kamera yanıt vermedi. Evdeki telefonda Bekçi’nin kamera olarak çalıştığından emin ol.")
    }
    private val gatherTimeout = Runnable { sendAnswer() }
    private val discTimer = Runnable { fail("Bağlantı koptu.") }

    private var pendingSnap: Bitmap? = null
    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val b = pendingSnap
        pendingSnap = null
        if (ok && b != null) saveBitmap(b) else toast("Kaydetmek için depolama izni gerekiyor.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = Prefs.code(this)
        if (c == null) { finish(); return }
        code = c

        Rtc.init(this)
        renderer = SurfaceViewRenderer(this).apply {
            init(Rtc.egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
        }

        val am = getSystemService(AudioManager::class.java)
        prevMode = am.mode
        @Suppress("DEPRECATION")
        prevSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = true
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        setContent {
            BekciTheme(forceDark = true) {
                LiveScreen(
                    renderer = renderer,
                    phase = phase.value,
                    message = message.value,
                    muted = muted.value,
                    hasAudio = hasAudio.value,
                    liveSince = liveSince.longValue,
                    onBack = { finish() },
                    onMute = { toggleMute() },
                    onSnap = { snapshot() },
                    onRetry = { connect() },
                    canTalk = canTalk.value,
                    talking = talking.value,
                    onTalk = { setTalking(it) },
                    torchAvail = torchAvail.value,
                    torchOn = torchOn.value,
                    onTorch = { toggleTorch() },
                    recording = recording.value,
                    recSince = recSince.longValue,
                    onRecord = { toggleRecording() }
                )
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) connect()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun connect() {
        cleanup(sendBye = false)
        sid = UUID.randomUUID().toString().replace("-", "").take(12)
        answered = false
        answerSent = false
        phase.value = Phase.Connecting
        message.value = "Kameraya bağlanılıyor…"
        try { renderer.clearImage() } catch (e: Exception) { }
        val mySid = sid
        chan = SignalChannel(
            Pairing.watchInbox(code), code,
            onOpen = {
                Signal.send(
                    Pairing.camInbox(code), code,
                    JSONObject().put("t", "call").put("sid", mySid).put("ses", true)
                ) { main.post { if (sid == mySid) fail("İstek gönderilemedi. İnternet bağlantını kontrol et.") } }
            },
            onMsg = { m -> main.post { onSignal(m) } }
        ).also { it.start() }
        main.postDelayed(offerTimeout, 40_000)
    }

    private fun onSignal(m: JSONObject) {
        if (m.optString("sid") != sid) return
        when (m.optString("t")) {
            "offer" -> if (!answered) {
                answered = true
                main.removeCallbacks(offerTimeout)
                message.value = "Görüntü açılıyor…"
                hasAudio.value = m.optBoolean("ses", false)
                answer(m.optString("sdp"), m.optBoolean("ptt", false))
            }
            "torch" -> {
                torchAvail.value = !m.optBoolean("yok", false)
                torchOn.value = m.optBoolean("on", false)
                torchAllowed = m.optBoolean("izin", true)
            }
            "bye" -> {
                val why = m.optString("msg")
                if (phase.value == Phase.Live) fail("Canlı yayın bitti" + (if (why.isNotEmpty()) " ($why)." else "."))
                else fail("Kamera canlı yayını başlatamadı" + (if (why.isNotEmpty()) " ($why)." else "."))
            }
        }
    }

    private fun answer(sdp: String, ptt: Boolean) {
        val mic: AudioTrack? =
            if (ptt && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val s = Rtc.factory.createAudioSource(MediaConstraints())
                    micSrc = s
                    Rtc.factory.createAudioTrack("w0", s).also { it.setEnabled(false) }
                } catch (e: Exception) { null }
            } else null
        micTrack = mic
        val p = Rtc.factory.createPeerConnection(Rtc.config(), object : PcObserver() {
            override fun onTrack(transceiver: RtpTransceiver?) {
                val tr = transceiver?.receiver?.track() ?: return
                main.post {
                    if (tr is VideoTrack) { tr.addSink(renderer); remoteVideo = tr }
                    if (tr is AudioTrack) { remoteAudio = tr; tr.setEnabled(!muted.value) }
                }
            }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) main.post { sendAnswer() }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                main.post { onState(s) }
            }
        })
        if (p == null) { fail("Bağlantı hazırlanamadı."); return }
        pc = p
        p.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                if (mic != null) {
                    val t = p.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }
                    if (t != null) {
                        t.sender.setTrack(mic, false)
                        t.setDirection(org.webrtc.RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                        main.post { canTalk.value = true }
                    }
                }
                p.createAnswer(object : Sdp() {
                    override fun onCreateSuccess(d: SessionDescription?) {
                        if (d == null) return
                        p.setLocalDescription(object : Sdp() {
                            override fun onSetSuccess() { main.postDelayed(gatherTimeout, 5000) }
                            override fun onSetFailure(e: String?) { main.post { fail("Bağlantı kurulamadı.") } }
                        }, d)
                    }
                    override fun onCreateFailure(e: String?) { main.post { fail("Bağlantı kurulamadı.") } }
                }, MediaConstraints())
            }
            override fun onSetFailure(e: String?) { main.post { fail("Kameradan gelen bilgi okunamadı.") } }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun sendAnswer() {
        if (answerSent) return
        val d = pc?.localDescription ?: return
        answerSent = true
        main.removeCallbacks(gatherTimeout)
        val mySid = sid
        Signal.send(
            Pairing.camInbox(code), code,
            JSONObject().put("t", "answer").put("sid", mySid).put("sdp", d.description)
        ) { main.post { if (sid == mySid) fail("Kameraya ulaşılamadı.") } }
        main.postDelayed(discTimer, 30_000)
    }

    private fun onState(s: PeerConnection.PeerConnectionState?) {
        when (s) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                main.removeCallbacks(discTimer)
                if (phase.value != Phase.Live) {
                    phase.value = Phase.Live
                    liveSince.longValue = SystemClock.elapsedRealtime()
                }
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                main.removeCallbacks(discTimer)
                main.postDelayed(discTimer, 8000)
            }
            PeerConnection.PeerConnectionState.FAILED -> fail(
                "Bağlantı kurulamadı. İki telefonun da internete bağlı olduğundan emin ol; mobil veride sorun olursa Wi‑Fi ile dene."
            )
            else -> {}
        }
    }

    private fun fail(msg: String) {
        if (phase.value == Phase.Failed) return
        phase.value = Phase.Failed
        message.value = msg
        cleanup(sendBye = true)
    }

    private fun cleanup(sendBye: Boolean) {
        listOf(offerTimeout, gatherTimeout, discTimer).forEach { main.removeCallbacks(it) }
        chan?.stop()
        chan = null
        if (sendBye && sid.isNotEmpty() && code.isNotEmpty()) {
            Signal.send(Pairing.camInbox(code), code, JSONObject().put("t", "bye").put("sid", sid))
        }
        stopRecording("Bağlantı bitti.")
        remoteAudio = null
        remoteVideo = null
        talking.value = false
        canTalk.value = false
        torchAvail.value = false
        torchOn.value = false
        try { pc?.dispose() } catch (e: Exception) { }
        pc = null
        try { micTrack?.dispose() } catch (e: Exception) { }
        try { micSrc?.dispose() } catch (e: Exception) { }
        micTrack = null
        micSrc = null
    }

    private fun toggleRecording() {
        if (recording.value) { stopRecording(null); return }
        val v = remoteVideo ?: return
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            toast("İzni verdikten sonra kayda tekrar bas.")
            return
        }
        val a = remoteAudio
        val rec = LiveRecorder(applicationContext, withAudio = a != null)
        recorder = rec
        v.addSink(rec)
        try { a?.addSink(rec) } catch (e: Throwable) { }
        recording.value = true
        recSince.longValue = SystemClock.elapsedRealtime()
        main.postDelayed(recMax, 10 * 60_000L)
    }

    private fun stopRecording(note: String?) {
        val rec = recorder ?: return
        recorder = null
        main.removeCallbacks(recMax)
        try { remoteVideo?.removeSink(rec) } catch (e: Exception) { }
        try { remoteAudio?.removeSink(rec) } catch (e: Throwable) { }
        recording.value = false
        val app = applicationContext
        rec.stop { ok ->
            val msg = if (ok) "Video galeride Bekçi klasörüne kaydedildi." else "Video kaydedilemedi."
            Toast.makeText(app, if (note != null) "$note $msg" else msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleTorch() {
        if (!torchAllowed && !torchOn.value) {
            toast("Flaş ayarı kapalı. Bekçi ana ekranındaki Flaş anahtarından açabilirsin.")
            return
        }
        val want = !torchOn.value
        torchOn.value = want
        Signal.send(
            Pairing.camInbox(code), code,
            JSONObject().put("t", "torch").put("sid", sid).put("on", want)
        ) { main.post { torchOn.value = !want; toast("Işık komutu gönderilemedi.") } }
    }

    private fun toggleMute() {
        muted.value = !muted.value
        if (!talking.value) remoteAudio?.setEnabled(!muted.value)
    }

    /** Walkie-talkie: while talking, the home sound is muted here so you don't hear yourself back. */
    private fun setTalking(on: Boolean) {
        val t = micTrack ?: return
        if (talking.value == on) return
        talking.value = on
        t.setEnabled(on)
        remoteAudio?.setEnabled(!on && !muted.value)
    }

    private fun snapshot() {
        renderer.addFrameListener({ bmp ->
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            main.post {
                if (Build.VERSION.SDK_INT < 29 &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingSnap = copy
                    storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else saveBitmap(copy)
            }
        }, 1f)
    }

    private fun saveBitmap(b: Bitmap) {
        val out = java.io.ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 92, out)
        if (Gallery.save(this, out.toByteArray(), Gallery.LIVE)) toast("Galeride Bekçi › Canlı klasörüne kaydedildi.")
        else toast("Fotoğraf kaydedilemedi.")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        if (code.isNotEmpty()) {
            cleanup(sendBye = phase.value != Phase.Failed)
            try { renderer.release() } catch (e: Exception) { }
            val am = getSystemService(AudioManager::class.java)
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = prevSpeaker
            am.mode = prevMode
        }
        super.onDestroy()
    }
}

@Composable
private fun LiveScreen(
    renderer: SurfaceViewRenderer,
    phase: LiveActivity.Phase,
    message: String,
    muted: Boolean,
    hasAudio: Boolean,
    liveSince: Long,
    onBack: () -> Unit,
    onMute: () -> Unit,
    onSnap: () -> Unit,
    onRetry: () -> Unit,
    canTalk: Boolean,
    talking: Boolean,
    onTalk: (Boolean) -> Unit,
    torchAvail: Boolean,
    torchOn: Boolean,
    onTorch: () -> Unit,
    recording: Boolean,
    recSince: Long,
    onRecord: () -> Unit
) {
    val glass = Color.Black.copy(alpha = 0.45f)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { renderer }, modifier = Modifier.fillMaxSize())

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onBack,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = glass, contentColor = Color.White)
            ) { Icon(Icons.Default.ArrowBack, contentDescription = "Geri") }
            Spacer(Modifier.weight(1f))
            if (phase == LiveActivity.Phase.Live && torchAvail) {
                FilledIconButton(
                    onClick = onTorch,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (torchOn) Color(0xFFFFD54F) else glass,
                        contentColor = if (torchOn) Color.Black else Color.White
                    )
                ) { Text("🔦", fontSize = 22.sp) }
                Spacer(Modifier.width(10.dp))
            }
            if (phase == LiveActivity.Phase.Live) {
                FilledIconButton(
                    onClick = onRecord,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (recording) Color(0xFFE5484D) else glass,
                        contentColor = Color.White
                    )
                ) {
                    if (recording) Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                    else Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFE5484D)))
                }
                Spacer(Modifier.width(10.dp))
                LiveBadge(liveSince)
            }
        }

        if (recording) {
            RecBadge(
                recSince,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 70.dp, end = 12.dp)
            )
        }

        if (talking) {
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xFFE5484D))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🎙  Konuşuyorsun, evde duyuluyor", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }

        if (phase != LiveActivity.Phase.Live) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (phase == LiveActivity.Phase.Connecting) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(20.dp))
                }
                Text(message, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                if (phase == LiveActivity.Phase.Failed) {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onRetry, modifier = Modifier.height(50.dp)) { Text("Tekrar dene") }
                }
            }
        }

        if (phase == LiveActivity.Phase.Live) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (hasAudio) RoundAction(if (muted) "🔇" else "🔊", if (muted) "Sesi aç" else "Sessiz", glass, onMute)
                if (canTalk) TalkButton(talking, onTalk)
                RoundAction("📸", "Kaydet", glass, onSnap)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FilledIconButton(
                        onClick = onBack,
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE5484D), contentColor = Color.White)
                    ) { Icon(Icons.Default.Close, contentDescription = "Bitir", modifier = Modifier.size(30.dp)) }
                    Spacer(Modifier.height(6.dp))
                    Text("Bitir", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun RoundAction(emoji: String, label: String, bg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = bg, contentColor = Color.White)
        ) { Text(emoji, fontSize = 26.sp) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun LiveBadge(since: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFE5484D)))
        Spacer(Modifier.width(8.dp))
        Text(
            "CANLI  " + "%02d:%02d".format(s / 60, s % 60),
            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
        )
    }
}

@Composable
private fun TalkButton(talking: Boolean, onTalk: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(80.dp)
                .clip(CircleShape)
                .background(if (talking) Color(0xFFE5484D) else Color.White)
                .border(4.dp, if (talking) Color.White.copy(alpha = 0.8f) else Color.Transparent, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onTalk(true)
                        try { tryAwaitRelease() } finally { onTalk(false) }
                    })
                },
            contentAlignment = Alignment.Center
        ) { Text("🎙", fontSize = 32.sp) }
        Spacer(Modifier.height(6.dp))
        Text(if (talking) "Bırak" else "Bas konuş", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecBadge(since: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) { now = SystemClock.elapsedRealtime(); delay(500) }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    val blink = (now / 600) % 2 == 0L
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE5484D))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (blink) Color.White else Color.Transparent))
        Spacer(Modifier.width(8.dp))
        Text("KAYIT  " + "%02d:%02d".format(s / 60, s % 60), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
