package com.bekci.app

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.webrtc.AudioTrackSink
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer

/**
 * Records the live stream as received on the watcher phone into an MP4 (H.264 + AAC),
 * then moves it to the gallery (Movies/Bekçi). Everything heavy runs on its own thread.
 */
class LiveRecorder(private val ctx: Context, private val withAudio: Boolean) : VideoSink, AudioTrackSink {

    private val thread = HandlerThread("bekci-kayit").apply { start() }
    private val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val file = File(ctx.cacheDir, "kayit-${System.currentTimeMillis()}.mp4")
    private val t0 = System.nanoTime()

    private var muxer: MediaMuxer? = null
    private var venc: MediaCodec? = null
    private var aenc: MediaCodec? = null
    private var vTrack = -1
    private var aTrack = -1
    private var muxStarted = false
    private var audioDisabled = !withAudio
    private val audioGiveUpAt = System.currentTimeMillis() + 3000L
    private val pending = ArrayList<Triple<Boolean, ByteArray, MediaCodec.BufferInfo>>()
    private var videoSamples = 0

    private var w = 0
    private var hgt = 0
    private var rotation = 0
    @Volatile private var stopping = false
    @Volatile private var busy = false
    private var lastFrameNs = 0L

    private var aRate = 0
    private var aCh = 0
    private var aNextPts = 0L

    init {
        h.post {
            try { muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) } catch (e: Exception) { }
        }
    }

    // ---------------- video ----------------

    override fun onFrame(frame: VideoFrame) {
        if (stopping || busy) return
        val now = System.nanoTime()
        if (now - lastFrameNs < 62_000_000L) return
        lastFrameNs = now
        val b = frame.buffer
        if (w == 0) {
            val s = minOf(1f, 1280f / maxOf(b.width, b.height))
            w = ((b.width * s).toInt()) and 1.inv()
            hgt = ((b.height * s).toInt()) and 1.inv()
            rotation = frame.rotation
            if (w < 16 || hgt < 16) { w = 0; return }
        }
        val i420 = try {
            val scaled = b.cropAndScale(0, 0, b.width, b.height, w, hgt)
            val x = scaled.toI420()
            scaled.release()
            x
        } catch (e: Exception) { null } ?: return
        busy = true
        val pts = (now - t0) / 1000L
        h.post {
            try { encodeVideo(i420, pts) } catch (e: Exception) { } finally { i420.release(); busy = false }
        }
    }

    private fun ensureVideo(): MediaCodec {
        venc?.let { return it }
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, hgt).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, if (w * hgt >= 900_000) 2_500_000 else 1_200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        venc = c
        try { muxer?.setOrientationHint(rotation) } catch (e: Exception) { }
        return c
    }

    private fun encodeVideo(b: VideoFrame.I420Buffer, pts: Long) {
        if (stopping) return
        val c = ensureVideo()
        val idx = c.dequeueInputBuffer(10_000)
        if (idx >= 0) {
            val img = c.getInputImage(idx)
            if (img != null) {
                copyPlane(b.dataY, b.strideY, img.planes[0], w, hgt)
                copyPlane(b.dataU, b.strideU, img.planes[1], w / 2, hgt / 2)
                copyPlane(b.dataV, b.strideV, img.planes[2], w / 2, hgt / 2)
            }
            c.queueInputBuffer(idx, 0, w * hgt * 3 / 2, pts, 0)
        }
        drain(c, video = true, eos = false)
    }

    private fun copyPlane(src0: ByteBuffer, sStride: Int, p: Image.Plane, pw: Int, ph: Int) {
        val src = src0.duplicate()
        val dst = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val row = ByteArray(pw)
        for (y in 0 until ph) {
            src.position(y * sStride)
            src.get(row, 0, pw)
            if (ps == 1) {
                dst.position(y * rs)
                dst.put(row, 0, pw)
            } else {
                val base = y * rs
                for (x in 0 until pw) {
                    val i = base + x * ps
                    if (i < dst.limit()) dst.put(i, row[x])
                }
            }
        }
    }

    // ---------------- audio ----------------

    override fun onData(
        audioData: ByteBuffer, bitsPerSample: Int, sampleRate: Int,
        numberOfChannels: Int, numberOfFrames: Int, absoluteCaptureTimestampMs: Long
    ) {
        if (stopping || audioDisabled || bitsPerSample != 16 || numberOfChannels < 1) return
        val n = minOf(numberOfFrames * numberOfChannels * 2, audioData.remaining())
        if (n <= 0) return
        val arr = ByteArray(n)
        audioData.duplicate().get(arr, 0, n)
        val arrival = (System.nanoTime() - t0) / 1000L
        h.post { try { encodeAudio(arr, sampleRate, numberOfChannels, arrival) } catch (e: Exception) { } }
    }

    private fun encodeAudio(pcm: ByteArray, rate: Int, ch: Int, arrivalUs: Long) {
        if (stopping || audioDisabled) return
        var c = aenc
        if (c == null) {
            if (muxStarted) { audioDisabled = true; return }
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
            }
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            aenc = c
            aRate = rate
            aCh = ch
        }
        if (rate != aRate || ch != aCh) return
        var off = 0
        while (off < pcm.size) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx < 0) break
            val buf = c.getInputBuffer(idx) ?: break
            buf.clear()
            val len = minOf(buf.remaining(), pcm.size - off)
            buf.put(pcm, off, len)
            val durUs = (len / (2L * ch)) * 1_000_000L / rate
            val pts = maxOf(arrivalUs, aNextPts)
            aNextPts = pts + durUs
            c.queueInputBuffer(idx, 0, len, pts, 0)
            off += len
        }
        drain(c, video = false, eos = false)
    }

    // ---------------- muxing ----------------

    private fun drain(c: MediaCodec, video: Boolean, eos: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idle = 0
        while (true) {
            val o = c.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || ++idle > 100) return
                continue
            }
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val m = muxer ?: return
                if (!muxStarted) {
                    if (video) vTrack = m.addTrack(c.outputFormat) else aTrack = m.addTrack(c.outputFormat)
                    maybeStartMuxer()
                } else if (!video) audioDisabled = true
                continue
            }
            if (o < 0) continue
            val buf = c.getOutputBuffer(o)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
            if (buf != null && info.size > 0) write(video, buf, info)
            c.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun maybeStartMuxer(force: Boolean = false) {
        if (muxStarted) return
        val m = muxer ?: return
        if (vTrack < 0) return
        val waitAudio = !audioDisabled && aTrack < 0 && System.currentTimeMillis() < audioGiveUpAt && !force
        if (waitAudio) return
        if (aTrack < 0) audioDisabled = true
        m.start()
        muxStarted = true
        for ((v, data, i) in pending) {
            val t = if (v) vTrack else aTrack
            if (t >= 0) {
                m.writeSampleData(t, ByteBuffer.wrap(data), i)
                if (v) videoSamples++
            }
        }
        pending.clear()
    }

    private fun write(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!muxStarted) maybeStartMuxer()
        if (muxStarted) {
            val t = if (video) vTrack else aTrack
            if (t < 0) return
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer?.writeSampleData(t, buf, info)
            if (video) videoSamples++
        } else if (pending.size < 600) {
            val copy = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(copy, 0, info.size)
            val i = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending += Triple(video, copy, i)
        }
    }

    private fun finish(c: MediaCodec?, video: Boolean) {
        c ?: return
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx >= 0) {
                val pts = if (video) (System.nanoTime() - t0) / 1000L else aNextPts
                c.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(c, video, eos = true)
        } catch (e: Exception) { }
    }

    /** Stops, finalizes the MP4 and saves it to the gallery. [done] runs on the main thread. */
    fun stop(done: (Boolean) -> Unit) {
        if (stopping) return
        stopping = true
        h.post {
            var ok = false
            try {
                finish(venc, true)
                finish(aenc, false)
                if (!muxStarted && vTrack >= 0) maybeStartMuxer(force = true)
                ok = muxStarted && videoSamples > 0
                try { if (muxStarted) muxer?.stop() } catch (e: Exception) { ok = false }
            } catch (e: Exception) {
                ok = false
            } finally {
                try { muxer?.release() } catch (e: Exception) { }
                try { venc?.stop(); venc?.release() } catch (e: Exception) { }
                try { aenc?.stop(); aenc?.release() } catch (e: Exception) { }
            }
            if (ok) ok = Gallery.saveVideo(ctx, file)
            file.delete()
            main.post { done(ok) }
            thread.quitSafely()
        }
    }
}
