package com.phonesteam.app.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Decodificador H.264 por hardware (MediaCodec) que desenha direto na Surface do renderizador.
 * O decoder só é criado quando chega o primeiro keyframe (precisamos do SPS/PPS).
 */
class VideoDecoder(private val surface: Surface, private val width: Int, private val height: Int) {

    private var codec: MediaCodec? = null
    @Volatile private var running = true
    private var outputThread: Thread? = null
    var decodedFrames = 0L
        private set

    /** Retorna false se o frame foi descartado (o chamador deve pedir um keyframe). */
    fun submit(data: ByteArray, offset: Int, length: Int, ptsUs: Long, keyframe: Boolean): Boolean {
        if (codec == null) {
            if (!keyframe) return false
            if (!start(data, offset, length)) return false
        }
        val c = codec ?: return false
        return try {
            val idx = c.dequeueInputBuffer(50_000)
            if (idx < 0) return false
            val buf = c.getInputBuffer(idx) ?: return false
            buf.clear()
            if (length > buf.capacity()) {
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                return false
            }
            buf.put(data, offset, length)
            c.queueInputBuffer(idx, 0, length, ptsUs, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "decoder em estado inválido", e)
            false
        }
    }

    private fun start(data: ByteArray, offset: Int, length: Int): Boolean {
        val nals = splitNals(data, offset, length)
        val sps = nals.firstOrNull { it.type == 7 } ?: return false
        val pps = nals.firstOrNull { it.type == 8 } ?: return false
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(data, sps.start, sps.end - sps.start).slice())
            setByteBuffer("csd-1", ByteBuffer.wrap(data, pps.start, pps.end - pps.start).slice())
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // tempo real
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            // Extensões de baixa latência de alguns fabricantes (ignoradas se não suportadas).
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        outputThread = Thread({ drainLoop(c) }, "PhoneSteam-decoder").apply { start() }
        Log.i(TAG, "Decoder iniciado ${width}x$height (${c.name})")
        return true
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true) // SurfaceTexture.timestamp = pts * 1000
                    decodedFrames++
                }
            } catch (e: IllegalStateException) {
                if (running) Log.w(TAG, "drain", e)
                return
            }
        }
    }

    fun release() {
        running = false
        outputThread?.join(200)
        codec?.runCatching { stop(); release() }
        codec = null
    }

    private data class Nal(val type: Int, val start: Int, val end: Int)

    /** Divide um buffer Annex-B; `start` inclui o start code (MediaCodec espera csd com start code). */
    private fun splitNals(d: ByteArray, off: Int, len: Int): List<Nal> {
        val starts = ArrayList<Int>()
        val end = off + len
        var i = off
        while (i + 3 < end) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0 &&
                (d[i + 2].toInt() == 1 || (d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1))
            ) {
                starts.add(i)
                i += if (d[i + 2].toInt() == 1) 3 else 4
            } else i++
        }
        return starts.mapIndexed { n, s ->
            val hdr = if (d[s + 2].toInt() == 1) s + 3 else s + 4
            Nal(d[hdr].toInt() and 0x1F, s, if (n + 1 < starts.size) starts[n + 1] else end)
        }
    }

    companion object { private const val TAG = "VideoDecoder" }
}
