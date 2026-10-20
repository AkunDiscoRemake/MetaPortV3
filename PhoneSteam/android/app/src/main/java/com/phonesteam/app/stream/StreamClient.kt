package com.phonesteam.app.stream

import android.util.Log
import android.view.Surface
import com.phonesteam.app.input.XrFrameState
import com.phonesteam.app.util.Quat
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Conexão com o host PhoneSteam (driver SteamVR rodando no PC local ou no PC na nuvem do GameHub).
 *
 *  - TCP 9945: handshake, vídeo H.264 lado a lado, háptico, lista de apps.
 *  - UDP 9944: pose a ~90 Hz (no modo nuvem também vai pelo TCP, para atravessar NAT/firewall).
 */
class StreamClient(
    private val address: String,
    private val hello: Protocol.Hello,
    private val cloudMode: Boolean,
    private val frameState: () -> XrFrameState,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String, connected: Boolean)
        fun onHaptic(controller: Int, duration: Float, frequency: Float, amplitude: Float) {}
        fun onAppList(json: String) {}
    }

    @Volatile private var running = false
    @Volatile var streaming = false
        private set
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private var readerThread: Thread? = null
    private var poseThread: Thread? = null

    @Volatile private var surface: Surface? = null
    private var decoder: VideoDecoder? = null
    private var config: Protocol.Config? = null
    private val framePoses = ConcurrentHashMap<Long, Quat>()

    var framesReceived = 0L
        private set
    var bytesReceived = 0L
        private set

    @Volatile private var surfaceChanged = false

    /** Chamado pelo renderizador; se o contexto GL for recriado, o decoder é refeito na nova Surface. */
    fun setSurface(s: Surface) {
        surface = s
        surfaceChanged = true
    }

    fun renderPoseFor(ptsUs: Long): Quat? = framePoses.remove(ptsUs)

    fun start() {
        if (running) return
        running = true
        readerThread = Thread(::runLoop, "PhoneSteam-net").apply { start() }
    }

    fun stop() {
        running = false
        streaming = false
        runCatching { socket?.close() }
        readerThread?.interrupt()
        poseThread?.interrupt()
        decoder?.release()
        decoder = null
    }

    fun requestAppList() = send(Protocol.MSG_APP_LIST_REQ, ByteArray(0))
    fun launchApp(key: String) = send(Protocol.MSG_LAUNCH_APP, key.toByteArray(Charsets.UTF_8))

    private fun send(type: Int, payload: ByteArray): Boolean {
        val o = out ?: return false
        return try {
            synchronized(writeLock) {
                o.write(Protocol.header(type, payload.size))
                if (payload.isNotEmpty()) o.write(payload)
                o.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun runLoop() {
        val (host, port) = Protocol.parseAddress(address)
        var attempt = 0
        while (running) {
            try {
                listener.onStatus("Conectando a $host:$port…", false)
                val s = Socket()
                s.tcpNoDelay = true
                s.receiveBufferSize = 4 shl 20
                s.connect(InetSocketAddress(host, port), 6000)
                socket = s
                out = s.getOutputStream()
                send(Protocol.MSG_HELLO, hello.encode())
                attempt = 0
                listener.onStatus("Conectado a $host — aguardando SteamVR", true)
                if (hello.mode == Protocol.MODE_STREAM) startPoseSender(s.inetAddress)
                readMessages(DataInputStream(s.getInputStream().buffered(1 shl 20)))
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "conexão: ${e.message}")
                listener.onStatus("Sem conexão (${e.message ?: "erro"}). Tentando de novo…", false)
            } finally {
                streaming = false
                poseThread?.interrupt()
                runCatching { socket?.close() }
                out = null
                decoder?.release(); decoder = null
            }
            attempt++
            try { Thread.sleep((500L * attempt).coerceAtMost(5000)) } catch (_: InterruptedException) { break }
        }
    }

    private fun readMessages(input: DataInputStream) {
        val header = ByteArray(Protocol.HEADER_SIZE)
        var payload = ByteArray(1 shl 20)
        while (running) {
            input.readFully(header)
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (h.int != Protocol.MAGIC) throw IllegalStateException("protocolo inválido")
            val type = h.get().toInt() and 0xFF
            h.get(); h.short
            val len = h.int
            if (len < 0 || len > (64 shl 20)) throw IllegalStateException("mensagem grande demais")
            if (len > payload.size) payload = ByteArray(len)
            input.readFully(payload, 0, len)
            bytesReceived += len + Protocol.HEADER_SIZE
            val b = ByteBuffer.wrap(payload, 0, len).order(ByteOrder.LITTLE_ENDIAN)
            when (type) {
                Protocol.MSG_CONFIG -> onConfig(Protocol.Config.decode(b))
                Protocol.MSG_VIDEO -> onVideo(b, payload, len)
                Protocol.MSG_HAPTIC -> {
                    val c = b.get().toInt(); b.get(); b.short
                    listener.onHaptic(c, b.float, b.float, b.float)
                }
                Protocol.MSG_APP_LIST -> listener.onAppList(String(payload, 0, len, Charsets.UTF_8))
            }
        }
    }

    private fun onConfig(c: Protocol.Config) {
        config = c
        decoder?.release(); decoder = null
        streaming = true
        listener.onStatus("SteamVR: ${c.eyeWidth}x${c.eyeHeight}/olho @ ${c.fps} Hz, ${c.bitrateMbps} Mbps", true)
    }

    private fun onVideo(b: ByteBuffer, raw: ByteArray, len: Int) {
        val c = config ?: return
        val frameIndex = b.int.toLong() and 0xFFFFFFFFL
        val q = Quat(b.float, b.float, b.float, b.float)
        val key = b.get().toInt() != 0
        b.get(); b.short
        val dataOffset = b.position()
        val surf = surface ?: return
        if (surfaceChanged) {
            surfaceChanged = false
            decoder?.release(); decoder = null
        }
        val dec = decoder ?: VideoDecoder(surf, c.eyeWidth * 2, c.eyeHeight).also { decoder = it }
        val pts = frameIndex * 1000
        framePoses[pts] = q
        if (framePoses.size > 120) framePoses.keys.filter { it < pts - 120_000 }.forEach { framePoses.remove(it) }
        framesReceived++
        if (!dec.submit(raw, dataOffset, len - dataOffset, pts, key)) {
            send(Protocol.MSG_REQUEST_IDR, ByteArray(0))
        }
    }

    private fun startPoseSender(hostAddr: java.net.InetAddress) {
        poseThread?.interrupt()
        poseThread = Thread({
            val udp = DatagramSocket()
            val buf = ByteBuffer.allocate(Protocol.POSE_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val tcpBuf = ByteBuffer.allocate(Protocol.POSE_BODY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            var seq = 0
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val state = frameState()
                    val ts = System.nanoTime() / 1000
                    val pkt = Protocol.udpPosePacket(buf, seq, ts, state)
                    runCatching { udp.send(DatagramPacket(pkt.array(), pkt.limit(), hostAddr, Protocol.UDP_POSE_PORT)) }
                    if (cloudMode) {
                        tcpBuf.clear()
                        Protocol.writePoseBody(tcpBuf, seq, ts, state)
                        send(Protocol.MSG_POSE, tcpBuf.array())
                    }
                    seq++
                    Thread.sleep(11)
                }
            } catch (_: InterruptedException) {
            } finally {
                udp.close()
            }
        }, "PhoneSteam-pose").apply { start() }
    }

    companion object {
        private const val TAG = "StreamClient"

        /** Conexão curta só de controle (lista/abre apps sem iniciar o streaming). */
        fun control(address: String, action: (StreamClient) -> Unit, listener: Listener): StreamClient {
            val hello = Protocol.Hello(0, 0, 0, Protocol.MODE_CONTROL, 0, 0f, 0f, 0f, 0f, 0f)
            val c = StreamClient(address, hello, false, { error("sem pose no modo controle") }, listener)
            c.start()
            Thread {
                repeat(60) {
                    if (c.out != null) { action(c); return@Thread }
                    Thread.sleep(100)
                }
            }.start()
            return c
        }
    }
}
