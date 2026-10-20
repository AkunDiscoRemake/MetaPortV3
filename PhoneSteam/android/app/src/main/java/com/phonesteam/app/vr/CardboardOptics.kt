package com.phonesteam.app.vr

import com.phonesteam.app.util.LensProfile
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.tan

/**
 * Geometria ótica do Cardboard: a partir do tamanho físico da tela e do perfil da lente,
 * calcula para cada olho o centro da lente na tela e o frustum (tangentes) visto através dela.
 */
class CardboardOptics(
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    xdpi: Float,
    ydpi: Float,
    val lens: LensProfile,
) {
    val metersPerPxX = 0.0254f / xdpi
    val metersPerPxY = 0.0254f / ydpi
    val screenWidthM = screenWidthPx * metersPerPxX
    val screenHeightM = screenHeightPx * metersPerPxY

    /** Tangentes positivas do frustum (esquerda, direita, cima, baixo). */
    data class Fov(val left: Float, val right: Float, val up: Float, val down: Float)

    /** Centro da lente em pixels (origem no canto inferior esquerdo, como gl_FragCoord). */
    fun lensCenterPx(eye: Int): FloatArray {
        val cx = screenWidthM / 2 + (if (eye == 0) -1 else 1) * lens.interLens / 2
        return floatArrayOf(cx / metersPerPxX, lens.trayToLensCenter / metersPerPxY)
    }

    private fun distort(t: Float): Float {
        val r2 = t * t
        return t * (1 + lens.k1 * r2 + lens.k2 * r2 * r2)
    }

    fun fov(eye: Int): Fov {
        val maxTan = tan(Math.toRadians(lens.maxFovDeg.toDouble())).toFloat()
        val half = screenWidthM / 2
        val outer = half - lens.interLens / 2     // distância lente → borda externa da tela
        val inner = lens.interLens / 2            // distância lente → centro da tela
        val up = screenHeightM - lens.trayToLensCenter
        val down = lens.trayToLensCenter
        fun t(d: Float) = min(maxTan, distort(abs(d) / lens.screenToLens))
        val o = t(outer); val i = t(inner)
        return if (eye == 0) Fov(o, i, t(up), t(down)) else Fov(i, o, t(up), t(down))
    }

    /** FOV simétrico usado no handshake (o host renderiza os dois olhos com estes valores espelhados). */
    fun leftEyeFov() = fov(0)
}
