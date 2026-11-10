package com.bekci.app

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.AudioSource
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpTransceiver
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.Inflater

/** One shared WebRTC factory per process. */
object Rtc {
    @Volatile private var ready = false
    lateinit var egl: EglBase
    lateinit var factory: PeerConnectionFactory

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        val app = ctx.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        egl = EglBase.create()
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
        ready = true
    }

    fun config(): PeerConnection.RTCConfiguration {
        val servers = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun.cloudflare.com:3478"
        ).map { PeerConnection.IceServer.builder(it).createIceServer() }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
    }
}

open class PcObserver : PeerConnection.Observer {
    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(c: IceCandidate?) {}
    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
    override fun onAddStream(s: MediaStream?) {}
    override fun onRemoveStream(s: MediaStream?) {}
    override fun onDataChannel(d: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
}

open class Sdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/**
 * Signaling messages travel through ntfy, compressed and encrypted with the
 * pairing key, on two private topics (camera inbox, watcher inbox).
 */
object Signal {
    private val exec = Executors.newSingleThreadExecutor()

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray {
        val i = Inflater()
        i.setInput(b)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 1_000_000) break
        }
        i.end()
        return out.toByteArray()
    }

    /** Blocking send; call off the main thread. */
    fun sendSync(topic: String, code: String, msg: JSONObject): Boolean {
        try {
            val enc = Pairing.encrypt(code, deflate(msg.toString().toByteArray(Charsets.UTF_8)))
            val b64 = Base64.encodeToString(enc, Base64.NO_WRAP)
            for (attempt in 1..3) {
                try {
                    if (b64.length < 3500) Ntfy.postText(topic, "s:$b64") else Ntfy.putFile(topic, enc)
                    return true
                } catch (e: Exception) {
                    Thread.sleep(1500L * attempt)
                }
            }
        } catch (e: Exception) { }
        return false
    }

    fun send(topic: String, code: String, msg: JSONObject, onError: (() -> Unit)? = null) {
        exec.execute { if (!sendSync(topic, code, msg)) onError?.invoke() }
    }

    fun decode(o: JSONObject, code: String): JSONObject? {
        return try {
            val att = o.optJSONObject("attachment")
            val enc: ByteArray = if (att != null) {
                Ntfy.download(att.getString("url"), 200_000)
            } else {
                val m = o.optString("message")
                if (!m.startsWith("s:")) return null
                Base64.decode(m.substring(2), Base64.NO_WRAP)
            }
            JSONObject(String(inflate(Pairing.decrypt(code, enc)), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }
}

/** Long-lived ntfy subscription that delivers decrypted signaling messages. */
class SignalChannel(
    private val topic: String,
    private val code: String,
    private val onOpen: () -> Unit,
    private val onMsg: (JSONObject) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var thread: Thread? = null
    private var opened = false

    fun start() {
        if (running) return
        running = true
        thread = Thread { loop() }.also { it.isDaemon = true; it.start() }
    }

    private fun loop() {
        var backoff = 2000L
        while (running) {
            try {
                val c = URL(Ntfy.HOST + topic + "/json").openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 15000
                c.readTimeout = 120000
                val r = c.inputStream.bufferedReader()
                try {
                    backoff = 2000L
                    while (running) {
                        val line = r.readLine() ?: break
                        val o = try { JSONObject(line) } catch (e: Exception) { null } ?: continue
                        val ev = o.optString("event")
                        if (ev == "open") {
                            if (!opened) { opened = true; onOpen() }
                        } else if (ev == "message") {
                            val t = o.optLong("time", 0L) * 1000L
                            if (t > 0 && System.currentTimeMillis() - t > 90_000L) continue
                            val m = Signal.decode(o, code) ?: continue
                            onMsg(m)
                        }
                    }
                } finally {
                    try { r.close() } catch (e: Exception) { }
                }
            } catch (e: Exception) {
            } finally {
                try { conn?.disconnect() } catch (e: Exception) { }
                conn = null
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
            backoff = minOf(backoff * 2, 30_000L)
        }
    }

    fun stop() {
        running = false
        val c = conn
        Thread { try { c?.disconnect() } catch (e: Exception) { } }.start()
        thread?.interrupt()
        thread = null
    }
}

/** Camera-phone side of one live session: captures camera (+mic) and offers it. */
class CamLive(
    private val ctx: Context,
    private val sid: String,
    private val audio: Boolean,
    private val sendSig: (JSONObject) -> Unit,
    private val onEnd: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var vsrc: VideoSource? = null
    private var asrc: AudioSource? = null
    private var offerSent = false
    private var closed = false
    private var retried = false
    private var audioSaved = false
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    private var prevVol = -1

    /** Route the watcher's push-to-talk voice to the loudspeaker at full volume. */
    @Suppress("DEPRECATION")
    private fun speakerOn() {
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            prevMode = am.mode
            prevSpeaker = am.isSpeakerphoneOn
            prevVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            audioSaved = true
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        } catch (e: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun speakerRestore() {
        if (!audioSaved) return
        audioSaved = false
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (prevVol >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVol, 0)
            am.isSpeakerphoneOn = prevSpeaker
            am.mode = prevMode
        } catch (e: Exception) { }
    }

    private val gatherTimeout = Runnable { sendOffer() }
    private val answerTimeout = Runnable { end("izleyici yanıt vermedi") }
    private val maxTimer = Runnable { end("süre doldu") }
    private val discTimer = Runnable { end("bağlantı koptu") }

    fun start() {
        if (closed) return
        try {
            Rtc.init(ctx)
            val f = Rtc.factory
            val en = Camera2Enumerator(ctx)
            val name = en.deviceNames.firstOrNull { en.isBackFacing(it) } ?: en.deviceNames.firstOrNull()
            if (name == null) { end("kamera bulunamadı"); return }
            val cap = en.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(err: String?) { main.post { retryCapture() } }
                override fun onCameraDisconnected() { main.post { retryCapture() } }
                override fun onCameraFreezed(err: String?) {}
                override fun onCameraOpening(n: String?) {}
                override fun onFirstFrameAvailable() {}
                override fun onCameraClosed() {}
            })
            capturer = cap
            val h = SurfaceTextureHelper.create("bekci-canli", Rtc.egl.eglBaseContext)
            helper = h
            val vs = f.createVideoSource(false)
            vsrc = vs
            cap.initialize(h, ctx, vs.capturerObserver)
            cap.startCapture(1280, 720, 15)

            val p = f.createPeerConnection(Rtc.config(), object : PcObserver() {
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                    if (s == PeerConnection.IceGatheringState.COMPLETE) main.post { sendOffer() }
                }
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                    main.post { onState(s) }
                }
            })
            if (p == null) { end("bağlantı hazırlanamadı"); return }
            pc = p
            p.addTrack(f.createVideoTrack("v0", vs), listOf("s0"))
            if (audio) {
                val mc = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
                }
                val a = f.createAudioSource(mc)
                asrc = a
                p.addTrack(f.createAudioTrack("a0", a), listOf("s0"))
            } else {
                // No ambient sound, but keep an audio line open for push-to-talk.
                p.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
            }
            speakerOn()
            p.createOffer(object : Sdp() {
                override fun onCreateSuccess(d: SessionDescription?) {
                    if (d == null) { main.post { end("teklif oluşturulamadı") }; return }
                    p.setLocalDescription(object : Sdp() {
                        override fun onSetSuccess() { main.postDelayed(gatherTimeout, 5000) }
                        override fun onSetFailure(e: String?) { main.post { end("teklif ayarlanamadı") } }
                    }, d)
                }
                override fun onCreateFailure(e: String?) { main.post { end("teklif oluşturulamadı") } }
            }, MediaConstraints())
            main.postDelayed(maxTimer, 15 * 60_000L)
        } catch (e: Exception) {
            end("canlı yayın başlatılamadı")
        }
    }

    private fun retryCapture() {
        if (closed || retried) return
        retried = true
        main.postDelayed({
            if (!closed) try {
                capturer?.stopCapture()
                capturer?.startCapture(1280, 720, 15)
            } catch (e: Exception) { }
        }, 1200)
    }

    private fun sendOffer() {
        if (offerSent || closed) return
        val d = pc?.localDescription ?: return
        offerSent = true
        main.removeCallbacks(gatherTimeout)
        sendSig(JSONObject().put("t", "offer").put("sid", sid).put("sdp", d.description).put("ses", audio).put("ptt", true))
        main.postDelayed(answerTimeout, 40_000)
    }

    fun onAnswer(sdp: String) {
        if (closed || sdp.isEmpty()) return
        main.removeCallbacks(answerTimeout)
        pc?.setRemoteDescription(object : Sdp() {
            override fun onSetFailure(e: String?) { main.post { end("cevap işlenemedi") } }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun onState(s: PeerConnection.PeerConnectionState?) {
        when (s) {
            PeerConnection.PeerConnectionState.CONNECTED -> main.removeCallbacks(discTimer)
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                main.removeCallbacks(discTimer)
                main.postDelayed(discTimer, 10_000)
            }
            PeerConnection.PeerConnectionState.FAILED,
            PeerConnection.PeerConnectionState.CLOSED -> end("bağlantı kurulamadı")
            else -> {}
        }
    }

    fun end(reason: String) {
        if (closed) return
        closed = true
        listOf(gatherTimeout, answerTimeout, maxTimer, discTimer).forEach { main.removeCallbacks(it) }
        try { pc?.dispose() } catch (e: Exception) { }
        pc = null
        try { capturer?.stopCapture() } catch (e: Exception) { }
        try { capturer?.dispose() } catch (e: Exception) { }
        capturer = null
        try { vsrc?.dispose() } catch (e: Exception) { }
        try { asrc?.dispose() } catch (e: Exception) { }
        try { helper?.dispose() } catch (e: Exception) { }
        vsrc = null; asrc = null; helper = null
        speakerRestore()
        onEnd(reason)
    }
}
