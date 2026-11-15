package com.bekci.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Which ways out of the local network exist: [host, srflx (via STUN), relay (via TURN), symmetric NAT 0/1]. */
object NetProbe {
    fun gather(ctx: Context, timeoutMs: Long = 9000): IntArray {
        return try {
            Rtc.init(ctx)
            val done = CountDownLatch(1)
            val pc = Rtc.factory.createPeerConnection(Rtc.config(), object : PcObserver() {
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                    if (s == PeerConnection.IceGatheringState.COMPLETE) done.countDown()
                }
            }) ?: return intArrayOf(0, 0, 0, 0)
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            pc.createOffer(object : Sdp() {
                override fun onCreateSuccess(d: SessionDescription?) { if (d != null) pc.setLocalDescription(Sdp(), d) }
            }, MediaConstraints())
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            val sdp = pc.localDescription?.description ?: ""
            pc.dispose()
            parse(sdp)
        } catch (e: Exception) {
            intArrayOf(0, 0, 0, 0)
        }
    }

    fun parse(sdp: String): IntArray {
        var host = 0; var srflx = 0; var relay = 0
        val mapped = HashMap<String, MutableSet<String>>() // local base port -> external ports seen
        for (line in sdp.lines()) {
            val l = line.trim()
            if (!l.startsWith("a=candidate:")) continue
            val f = l.split(" ")
            val ti = f.indexOf("typ")
            if (ti < 0 || ti + 1 >= f.size) continue
            when (f[ti + 1]) {
                "host" -> host++
                "relay" -> relay++
                "srflx" -> {
                    srflx++
                    val rp = f.indexOf("rport")
                    if (f.getOrNull(2)?.lowercase() == "udp" && rp > 0 && rp + 1 < f.size) {
                        mapped.getOrPut(f[rp + 1]) { HashSet() } += f.getOrElse(5) { "" }
                    }
                }
            }
        }
        val symmetric = mapped.values.any { it.size > 1 }
        return intArrayOf(host, srflx, relay, if (symmetric) 1 else 0)
    }
}

class DiagRow(val title: String) {
    /** 0 waiting, 1 running, 2 ok, 3 warning, 4 failed */
    var state by mutableIntStateOf(0)
    var detail by mutableStateOf("")
}

/** Step-by-step connection check, run from the watcher phone. */
class Diagnostics(private val ctx: Context, private val code: String) {
    val rows = listOf(
        DiagRow("İnternet bağlantısı"),
        DiagRow("Bekçi sunucusu (ntfy.sh)"),
        DiagRow("Telefon saati"),
        DiagRow("Evdeki kamera"),
        DiagRow("Evdeki kameranın ağı"),
        DiagRow("Bu telefonun ağı"),
        DiagRow("Uyarılar")
    )
    var verdict by mutableStateOf("")
    var running by mutableStateOf(false)

    private suspend fun set(i: Int, state: Int, detail: String) = withContext(Dispatchers.Main) {
        rows[i].state = state
        rows[i].detail = detail
    }

    suspend fun run() {
        withContext(Dispatchers.Main) {
            running = true
            verdict = ""
            rows.forEach { it.state = 0; it.detail = "" }
        }
        withContext(Dispatchers.IO) { steps() }
        withContext(Dispatchers.Main) { running = false }
    }

    private fun netLabel(): Pair<Boolean, String> {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false to "Bağlı bir ağ yok."
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobil veri"
                else -> "ağ"
            }
            val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ok to (if (ok) "Bağlı ($kind)." else "$kind var ama internete çıkamıyor.")
        } catch (e: Exception) { true to "Kontrol edilemedi." }
    }

    private suspend fun steps() {
        // 1. internet
        set(0, 1, "")
        val (netOk, netText) = netLabel()
        set(0, if (netOk) 2 else 4, netText)
        if (!netOk) { finish("Bu telefonun interneti yok. Wi‑Fi’a ya da mobil veriye bağlanıp tekrar dene."); return }

        // 2. server + 3. clock (from the server's Date header)
        set(1, 1, "")
        var serverDate = 0L
        val ms = try {
            val t0 = System.currentTimeMillis()
            val c = URL(Ntfy.HOST + "v1/health").openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 10000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            serverDate = c.getHeaderFieldDate("Date", 0L)
            c.disconnect()
            if (!body.contains("true")) -1L else System.currentTimeMillis() - t0
        } catch (e: Exception) { -1L }
        if (ms < 0) {
            set(1, 4, "Sunucuya ulaşılamadı. Ağ ntfy.sh’i engelliyor olabilir (bazı okul/iş Wi‑Fi’ları).")
            finish("Sunucuya ulaşılamıyor; uyarılar ve canlı yayın bu ağda çalışmaz."); return
        }
        set(1, if (ms > 3000) 3 else 2, "Ulaşıldı ($ms ms)." + if (ms > 3000) " Bağlantı yavaş." else "")
        set(2, 1, "")
        if (serverDate > 0) {
            ServerClock.update(ctx, serverDate)
            val diff = (serverDate - System.currentTimeMillis()) / 1000
            val ad = kotlin.math.abs(diff)
            set(2, if (ad > 60) 3 else 2,
                if (ad <= 5) "Doğru." else "Sunucudan $ad sn ${if (diff > 0) "geri" else "ileri"}; Bekçi bunu otomatik düzeltiyor.")
        } else set(2, 3, "Kontrol edilemedi.")

        // 4-5. ask the camera for status and a network probe, on the alert topic
        set(3, 1, "Evdeki kameraya soruluyor…")
        val q = LinkedBlockingQueue<JSONObject>()
        val opened = CountDownLatch(1)
        var conn: HttpURLConnection? = null
        val reader = Thread {
            try {
                val c = URL(Ntfy.HOST + Pairing.topic(code) + "/json").openConnection() as HttpURLConnection
                conn = c; c.connectTimeout = 10000; c.readTimeout = 60000
                c.inputStream.bufferedReader().forEachLine { line ->
                    val o = try { JSONObject(line) } catch (e: Exception) { null }
                    if (o != null) { if (o.optString("event") == "open") opened.countDown() else q.add(o) }
                }
            } catch (e: Exception) { }
        }.apply { isDaemon = true; start() }
        try {
            if (!opened.await(15, TimeUnit.SECONDS)) {
                set(3, 4, "Sunucu kanalı açılamadı."); finish("Sunucuyla bağlantı kararsız. Biraz sonra tekrar dene."); return
            }
            val t0 = System.currentTimeMillis()
            val sid = UUID.randomUUID().toString().take(8)
            Signal.sendSync(Pairing.camInbox(code), code, JSONObject().put("t", "status").put("sid", sid))
            val st = waitFor(q, "st_", 25_000)
            if (st == null) {
                set(3, 4, "Yanıt vermedi. Evdeki telefonda izleme açık mı, interneti var mı, Bekçi güncel mi (2.19+)?")
                set(4, 0, "Kamera yanıt vermediği için atlandı.")
                finish("Evdeki kameraya ulaşılamıyor. Evdeki telefonu kontrol et: Bekçi açık ve “İzliyor” durumda olmalı, interneti olmalı.")
                set5(); return
            }
            val f = st.split("_")
            val rtt = (System.currentTimeMillis() - t0) / 1000.0
            set(3, 2, "Yanıt verdi (${"%.1f".format(rtt)} sn). Pil %${f.getOrNull(1) ?: "?"}" +
                (if (f.getOrNull(2) == "1") ", şarja takılı" else ", şarja takılı değil") +
                (if (f.getOrNull(3) == "1") ", hareket algılama açık." else ", hareket algılama kapalı."))

            set(4, 1, "Ağ yapısı ölçülüyor…")
            Signal.sendSync(Pairing.camInbox(code), code, JSONObject().put("t", "diag").put("sid", sid))
            val dg = waitFor(q, "dg_", 25_000)
            val cam = dg?.split("_")?.drop(1)?.mapNotNull { it.toIntOrNull() }
            if (cam == null || cam.size < 4) set(4, 3, "Ölçülemedi.") else set(4, netStateOf(cam), netText(cam))

            val mine = set5()
            if (cam != null && cam.size >= 4) liveVerdict(cam, mine)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
            reader.interrupt()
        }
        alertsRow()
    }

    private suspend fun set5(): List<Int> {
        set(5, 1, "Ağ yapısı ölçülüyor…")
        val mine = NetProbe.gather(ctx).toList()
        set(5, netStateOf(mine), netText(mine))
        return mine
    }

    private fun netStateOf(r: List<Int>) = when {
        r[1] > 0 && r[3] == 0 -> 2
        r[1] > 0 || r[2] > 0 -> 3
        else -> 4
    }

    private fun netText(r: List<Int>): String = when {
        r[1] > 0 && r[3] == 0 -> "Doğrudan bağlantıya uygun."
        r[1] > 0 -> "Kısıtlı ağ (simetrik NAT): doğrudan bağlantı zor" + (if (r[2] > 0) ", aktarma sunucusu kullanılabilir." else ".")
        r[2] > 0 -> "İnternete yalnızca aktarma sunucusuyla çıkabiliyor."
        else -> "Dışarıya bağlantı yolu bulunamadı."
    }

    private suspend fun liveVerdict(cam: List<Int>, mine: List<Int>) {
        val camDirect = cam[1] > 0
        val myDirect = mine[1] > 0
        val bothSym = cam[3] == 1 && mine[3] == 1
        val relay = cam[2] > 0 && mine[2] > 0
        withContext(Dispatchers.Main) {
            verdict = when {
                camDirect && myDirect && !bothSym -> "✅ Her şey yolunda görünüyor. Canlı yayın doğrudan bağlanmalı."
                relay -> "⚠️ Doğrudan bağlantı zor, ama canlı yayın aktarma sunucusu üzerinden bağlanabilir (biraz daha gecikmeli)."
                else -> "❌ Bu iki ağ arasında canlı yayın bağlanamayabilir. Telefonlardan birini (tercihen izleyiciyi) Wi‑Fi’a bağlayıp tekrar dene. Uyarılar yine de çalışır."
            }
        }
    }

    private suspend fun alertsRow() {
        val problems = ArrayList<String>()
        if (!WatchService.running) problems += "uyarılar kapalı"
        try {
            if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName))
                problems += "pil kısıtlaması açık (uyarılar gecikebilir)"
        } catch (e: Exception) { }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) problems += "bildirim izni yok"
        set(6, if (problems.isEmpty()) 2 else 3, if (problems.isEmpty()) "Açık ve hazır." else problems.joinToString(", ").replaceFirstChar { it.uppercase() } + ".")
        withContext(Dispatchers.Main) { if (verdict.isEmpty()) verdict = "Test tamamlandı." }
    }

    private suspend fun finish(text: String) {
        withContext(Dispatchers.Main) { verdict = "❌ $text" }
        alertsRow()
    }

    private fun waitFor(q: LinkedBlockingQueue<JSONObject>, prefix: String, ms: Long): String? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return null
            val o = q.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (o.optString("event") != "message") continue
            val m = o.optString("message")
            if (m.startsWith(prefix)) return m
        }
    }
}
