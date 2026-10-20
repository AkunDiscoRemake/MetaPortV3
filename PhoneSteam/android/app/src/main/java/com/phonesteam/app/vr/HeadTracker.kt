package com.phonesteam.app.vr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.phonesteam.app.util.Quat
import com.phonesteam.app.util.Vec3

/**
 * Rastreamento de cabeça 3DoF usando o sensor de vetor de rotação do Android (fusão de
 * giroscópio + acelerômetro + magnetômetro feita pelo próprio sistema), mais um modelo de
 * pescoço para gerar um pouco de translação natural.
 */
class HeadTracker(context: Context, private val eyeHeight: Float) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile var displayRotation = Surface.ROTATION_90
    @Volatile private var raw = Quat()
    @Volatile private var yawOffset = Quat()
    private var needsRecenter = true

    val available get() = sensor != null

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    /** Zera o yaw: a direção para onde o usuário olha passa a ser "para frente". */
    fun recenter() { needsRecenter = true }

    override fun onSensorChanged(event: SensorEvent) {
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, event.values) // w, x, y, z
        val r = toOpenVr(Quat(q[1], q[2], q[3], q[0]), displayRotation)
        raw = r
        if (needsRecenter) {
            yawOffset = Quat.axisAngle(0f, 1f, 0f, -r.yaw())
            needsRecenter = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun orientation(): Quat = (yawOffset * raw).normalized()

    /** Posição dos olhos (centro) aplicando um modelo de pescoço. */
    fun position(rot: Quat = orientation()): Vec3 {
        val neckToEye = Vec3(0f, NECK_UP, -NECK_FORWARD)
        val neckBase = Vec3(0f, eyeHeight - NECK_UP, NECK_FORWARD)
        return neckBase + rot.rotate(neckToEye)
    }

    companion object {
        /**
         * Converte a orientação do Android (dispositivo → mundo Leste/Norte/Cima) para a cabeça no
         * OpenVR (X direita, Y cima, -Z para frente), considerando a rotação da tela.
         */
        fun toOpenVr(deviceToWorld: Quat, displayRotation: Int): Quat {
            // Mundo Android -> mundo OpenVR = Rx(-90°)
            val worldFix = Quat.axisAngle(1f, 0f, 0f, (-Math.PI / 2).toFloat())
            // Dispositivo -> visor: em paisagem a tela fica de lado.
            val screenFix = when (displayRotation) {
                Surface.ROTATION_270 -> Quat.axisAngle(0f, 0f, 1f, (Math.PI / 2).toFloat())
                Surface.ROTATION_180 -> Quat.axisAngle(0f, 0f, 1f, Math.PI.toFloat())
                Surface.ROTATION_0 -> Quat()
                else -> Quat.axisAngle(0f, 0f, 1f, (-Math.PI / 2).toFloat())
            }
            return (worldFix * deviceToWorld * screenFix).normalized()
        }

        private const val NECK_UP = 0.075f
        private const val NECK_FORWARD = 0.08f
    }
}
