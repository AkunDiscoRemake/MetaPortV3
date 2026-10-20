package com.phonesteam.app

import android.view.Surface
import com.phonesteam.app.util.Quat
import com.phonesteam.app.util.Vec3
import com.phonesteam.app.vr.HeadTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadMathTest {

    private fun assertVec(expected: Vec3, actual: Vec3) {
        assertEquals("x", expected.x, actual.x, 1e-4f)
        assertEquals("y", expected.y, actual.y, 1e-4f)
        assertEquals("z", expected.z, actual.z, 1e-4f)
    }

    /**
     * Celular em paisagem (topo do aparelho para a esquerda), tela para o rosto, olhando para o Norte
     * no horizonte. Eixos do aparelho no mundo Leste/Norte/Cima:
     * X = Cima, Y = Oeste, Z (saindo da tela) = Sul.
     */
    @Test
    fun landscapeLookingNorthIsForward() {
        val deviceToWorld = Quat.fromBasis(Vec3(0f, 0f, 1f), Vec3(-1f, 0f, 0f), Vec3(0f, -1f, 0f))
        val head = HeadTracker.toOpenVr(deviceToWorld, Surface.ROTATION_90)
        assertVec(Vec3(0f, 0f, -1f), head.rotate(Vec3(0f, 0f, -1f))) // frente
        assertVec(Vec3(0f, 1f, 0f), head.rotate(Vec3(0f, 1f, 0f)))   // cima
        assertVec(Vec3(1f, 0f, 0f), head.rotate(Vec3(1f, 0f, 0f)))   // direita
    }

    @Test
    fun lookingUpPitchesHead() {
        // Mesmo segurar, mas inclinado 90° para cima: a tela (Z) aponta para baixo, o "para frente" para cima.
        val deviceToWorld = Quat.fromBasis(Vec3(0f, -1f, 0f), Vec3(-1f, 0f, 0f), Vec3(0f, 0f, -1f))
        val head = HeadTracker.toOpenVr(deviceToWorld, Surface.ROTATION_90)
        assertVec(Vec3(0f, 1f, 0f), head.rotate(Vec3(0f, 0f, -1f)))
    }

    @Test
    fun yawAndBasisRoundTrip() {
        val q = Quat.axisAngle(0f, 1f, 0f, 0.7f)
        assertEquals(0.7f, q.yaw(), 1e-4f)
        val back = Quat.fromBasis(q.rotate(Vec3(1f, 0f, 0f)), q.rotate(Vec3(0f, 1f, 0f)), q.rotate(Vec3(0f, 0f, 1f)))
        assertEquals(1f, kotlin.math.abs(q.x * back.x + q.y * back.y + q.z * back.z + q.w * back.w), 1e-4f)
    }
}
