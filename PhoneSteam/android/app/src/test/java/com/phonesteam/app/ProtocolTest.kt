package com.phonesteam.app

import com.phonesteam.app.input.ControllerFlags
import com.phonesteam.app.input.ControllerState
import com.phonesteam.app.input.InputSource
import com.phonesteam.app.input.XrFrameState
import com.phonesteam.app.stream.Protocol
import com.phonesteam.app.util.Quat
import com.phonesteam.app.util.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Garante que o layout bate com docs/PROTOCOL.md e com os structs de host/src/protocol.h. */
class ProtocolTest {

    private fun state() = XrFrameState(
        Quat(0f, 0.3826834f, 0f, 0.9238795f), Vec3(0f, 1.65f, 0f),
        ControllerState(),
        ControllerState(
            ControllerFlags.CONNECTED or ControllerFlags.POSE_VALID, InputSource.JOYCON,
            Vec3(0.2f, 1.2f, -0.3f), Quat(), 0b100, 0.75f, 0.5f, -1f, 1f,
        ),
    )

    @Test
    fun posePacketLayout() {
        val buf = ByteBuffer.allocate(Protocol.POSE_PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val pkt = Protocol.udpPosePacket(buf, 42, 123456789L, state())
        assertEquals(152, pkt.limit())
        val b = pkt.array()
        assertEquals("PSTM", String(b, 0, 4, Charsets.US_ASCII))
        val r = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, r.get(4).toInt())                 // version
        assertEquals(Protocol.MSG_POSE, r.get(5).toInt()) // type
        assertEquals(42, r.getInt(8))                     // sequence
        assertEquals(123456789L, r.getLong(12))           // timestamp
        assertEquals(0.3826834f, r.getFloat(24), 1e-6f)   // head qy
        assertEquals(1.65f, r.getFloat(40), 1e-6f)        // head py
        assertEquals(0, r.get(48).toInt())                // controle esquerdo desconectado
        assertEquals(3, r.get(100).toInt())               // flags do direito
        assertEquals(1, r.get(101).toInt())               // source = Joy-Con
        assertEquals(0b100, r.getInt(132))                // botões
        assertEquals(0.75f, r.getFloat(136), 1e-6f)       // gatilho
        assertEquals(1f, r.getFloat(148), 1e-6f)          // stickY
    }

    @Test
    fun helloLayout() {
        val h = Protocol.Hello(1024, 1152, 72, Protocol.MODE_STREAM, 30, 0.064f, 1.2f, 1.0f, 1.1f, 1.3f).encode()
        assertEquals(28, h.size)
        val r = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1024, r.getShort(0).toInt())
        assertEquals(72, r.get(4).toInt())
        assertEquals(1, r.get(5).toInt())
        assertEquals(1.3f, r.getFloat(24), 1e-6f)
    }

    @Test
    fun headerLayout() {
        val h = ByteBuffer.wrap(Protocol.header(Protocol.MSG_VIDEO, 1000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Protocol.MAGIC, h.getInt(0))
        assertEquals(4, h.get(4).toInt())
        assertEquals(1000, h.getInt(8))
    }

    @Test
    fun parseAddress() {
        assertEquals("192.168.0.10" to 9945, Protocol.parseAddress("192.168.0.10"))
        assertEquals("pc.gamehub.cloud" to 30000, Protocol.parseAddress(" pc.gamehub.cloud:30000 "))
        assertEquals("1.2.3.4" to 9000, Protocol.parseAddress("phonesteam://1.2.3.4:9000"))
    }
}
