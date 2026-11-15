package com.bekci.app

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs on every build (GitHub Actions). The network tests talk to the real ntfy.sh server. */
class ProtocolTest {

    @Test fun encryptionRoundTrip() {
        val code = Pairing.newCode()
        val data = ByteArray(5000).also { SecureRandom().nextBytes(it) }
        assertArrayEquals(data, Pairing.decrypt(code, Pairing.encrypt(code, data)))
    }

    @Test fun wrongKeyIsRejected() {
        val blob = Pairing.encrypt(Pairing.newCode(), "gizli".toByteArray())
        try {
            Pairing.decrypt(Pairing.newCode(), blob)
            fail("başka anahtarla çözülmemeliydi")
        } catch (e: Exception) { }
    }

    @Test fun topicsAreStableAndSeparate() {
        val c = Pairing.newCode()
        assertEquals(Pairing.topic(c), Pairing.topic(c.lowercase().replace("-", " ")))
        val all = setOf(Pairing.topic(c), Pairing.camInbox(c), Pairing.watchInbox(c), Pairing.pairTopic(c))
        assertEquals(4, all.size)
        assertNotEquals(Pairing.topic(c), Pairing.topic(Pairing.newCode()))
        assertTrue(Pairing.topic(c).length <= 64)
        val link = Pairing.newLinkKey()
        assertEquals(24, Pairing.normalize(link).length)
    }

    @Test fun signalMessageRoundTrip() {
        val code = Pairing.newLinkKey()
        val msg = JSONObject().put("t", "offer").put("sid", "abc").put("sdp", "v=0\r\n".repeat(200))
        val b64 = java.util.Base64.getEncoder().encodeToString(Signal.pack(code, msg))
        val back = Signal.decode(JSONObject().put("message", "s:$b64"), code)
        assertNotNull(back)
        assertEquals(msg.toString(), back.toString())
        assertEquals(null, Signal.decode(JSONObject().put("message", "s:$b64"), Pairing.newLinkKey()))
    }

    @Test fun natParsing() {
        val sdp = """
            a=candidate:1 1 udp 2122260223 192.168.1.5 50000 typ host
            a=candidate:2 1 udp 1686052607 85.1.2.3 61000 typ srflx raddr 192.168.1.5 rport 50000
            a=candidate:3 1 udp 1686052607 85.1.2.3 61001 typ srflx raddr 192.168.1.5 rport 50000
            a=candidate:4 1 udp 41885439 34.1.1.1 40000 typ relay raddr 85.1.2.3 rport 61000
        """.trimIndent()
        assertArrayEquals(intArrayOf(1, 2, 1, 1), NetProbe.parse(sdp))
        assertArrayEquals(intArrayOf(1, 1, 0, 0), NetProbe.parse(sdp.lines().take(2).joinToString("\n")))
    }

    /** Real server: small (text) and large (attachment) messages must arrive, encrypted, in order. */
    @Test fun ntfyRoundTrip() {
        val code = Pairing.newLinkKey()
        val topic = Pairing.camInbox(code)
        val got = LinkedBlockingQueue<JSONObject>()
        val opened = CountDownLatch(1)
        val ch = SignalChannel(topic, code, onOpen = { opened.countDown() }, onMsg = { got.add(it) })
        ch.start()
        try {
            assumeTrue("ntfy.sh'e ulaşılamadı", opened.await(20, TimeUnit.SECONDS))
            assertTrue(Signal.sendSync(topic, code, JSONObject().put("t", "call").put("sid", "s1")))
            val m1 = got.poll(20, TimeUnit.SECONDS)
            assertNotNull("küçük mesaj gelmedi", m1)
            assertEquals("call", m1!!.getString("t"))

            val r = SecureRandom()
            val big = (1..9000).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[r.nextInt(32)] }.joinToString("")
            assertTrue(Signal.sendSync(topic, code, JSONObject().put("t", "answer").put("sid", "s1").put("sdp", big)))
            val m2 = got.poll(30, TimeUnit.SECONDS)
            assertNotNull("büyük mesaj (ek dosya) gelmedi", m2)
            assertEquals(big, m2!!.getString("sdp"))
        } finally {
            ch.stop()
        }
    }

    /** Locked pairing handshake shape: pair request decrypts with the QR code, and the link key works. */
    @Test fun pairingRoundTripOverServer() {
        val qr = Pairing.newCode()
        val link = Pairing.newLinkKey()
        val topic = Pairing.pairTopic(qr)
        val got = LinkedBlockingQueue<JSONObject>()
        val opened = CountDownLatch(1)
        val ch = SignalChannel(topic, qr, onOpen = { opened.countDown() }, onMsg = { got.add(it) })
        ch.start()
        try {
            assumeTrue("ntfy.sh'e ulaşılamadı", opened.await(20, TimeUnit.SECONDS))
            assertTrue(Signal.sendSync(topic, qr, JSONObject().put("t", "pair").put("sid", "x").put("id", "w1").put("key", link)))
            val m = got.poll(20, TimeUnit.SECONDS)
            assertNotNull(m)
            assertEquals(link, m!!.getString("key"))
            assertTrue(Pairing.normalize(m.getString("key")).length >= 24)
        } finally {
            ch.stop()
        }
    }
}
