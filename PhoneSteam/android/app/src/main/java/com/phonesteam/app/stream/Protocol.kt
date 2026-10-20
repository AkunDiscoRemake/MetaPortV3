package com.phonesteam.app.stream

import com.phonesteam.app.input.ControllerState
import com.phonesteam.app.input.XrFrameState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Constantes e codificação do protocolo — ver docs/PROTOCOL.md. */
object Protocol {
    const val MAGIC = 0x4D545350 // "PSTM" em little-endian
    const val VERSION = 1
    const val UDP_POSE_PORT = 9944
    const val TCP_PORT = 9945
    const val HEADER_SIZE = 12

    const val MSG_POSE = 1
    const val MSG_HELLO = 2
    const val MSG_CONFIG = 3
    const val MSG_VIDEO = 4
    const val MSG_APP_LIST_REQ = 5
    const val MSG_APP_LIST = 6
    const val MSG_LAUNCH_APP = 7
    const val MSG_HAPTIC = 8
    const val MSG_REQUEST_IDR = 9

    const val MODE_CONTROL = 0
    const val MODE_STREAM = 1

    const val POSE_BODY_SIZE = 4 + 8 + 16 + 12 + 2 * 52
    const val POSE_PACKET_SIZE = 8 + POSE_BODY_SIZE

    fun header(type: Int, length: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC).put(type.toByte()).put(0).putShort(0).putInt(length).array()

    data class Hello(
        val eyeWidth: Int, val eyeHeight: Int, val fps: Int, val mode: Int, val bitrateMbps: Int,
        val ipd: Float, val tanLeft: Float, val tanRight: Float, val tanUp: Float, val tanDown: Float,
    ) {
        fun encode(): ByteArray = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(eyeWidth.toShort()).putShort(eyeHeight.toShort())
            .put(fps.toByte()).put(mode.toByte()).putShort(bitrateMbps.toShort())
            .putFloat(ipd).putFloat(tanLeft).putFloat(tanRight).putFloat(tanUp).putFloat(tanDown)
            .array()
    }

    data class Config(val eyeWidth: Int, val eyeHeight: Int, val fps: Int, val codec: Int, val bitrateMbps: Int) {
        companion object {
            fun decode(b: ByteBuffer) = Config(
                b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF,
                b.get().toInt() and 0xFF, b.get().toInt() and 0xFF, b.short.toInt() and 0xFFFF,
            )
        }
    }

    /** Corpo da pose (sem magic/version) — usado tanto no UDP quanto no TCP. */
    fun writePoseBody(b: ByteBuffer, seq: Int, timestampUs: Long, s: XrFrameState) {
        b.putInt(seq).putLong(timestampUs)
        s.headRotation.let { b.putFloat(it.x).putFloat(it.y).putFloat(it.z).putFloat(it.w) }
        s.headPosition.let { b.putFloat(it.x).putFloat(it.y).putFloat(it.z) }
        writeController(b, s.left)
        writeController(b, s.right)
    }

    private fun writeController(b: ByteBuffer, c: ControllerState) {
        b.put(c.flags.toByte()).put(c.source.code.toByte()).putShort(0)
        b.putFloat(c.position.x).putFloat(c.position.y).putFloat(c.position.z)
        b.putFloat(c.rotation.x).putFloat(c.rotation.y).putFloat(c.rotation.z).putFloat(c.rotation.w)
        b.putInt(c.buttons)
        b.putFloat(c.trigger).putFloat(c.grip).putFloat(c.stickX).putFloat(c.stickY)
    }

    fun udpPosePacket(buf: ByteBuffer, seq: Int, timestampUs: Long, s: XrFrameState): ByteBuffer {
        buf.clear()
        buf.putInt(MAGIC).put(VERSION.toByte()).put(MSG_POSE.toByte()).putShort(0)
        writePoseBody(buf, seq, timestampUs, s)
        buf.flip()
        return buf
    }

    /** Divide "host[:porta]". */
    fun parseAddress(addr: String): Pair<String, Int> {
        val a = addr.trim().removePrefix("phonesteam://")
        val i = a.lastIndexOf(':')
        if (i > 0 && a.indexOf(':') == i) {
            val port = a.substring(i + 1).toIntOrNull()
            if (port != null) return a.substring(0, i) to port
        }
        return a to TCP_PORT
    }
}
