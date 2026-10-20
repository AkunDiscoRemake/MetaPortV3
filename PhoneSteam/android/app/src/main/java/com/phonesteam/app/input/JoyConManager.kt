package com.phonesteam.app.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.phonesteam.app.util.Quat
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Suporte a Joy-Cons (e controles Pro / gamepads genéricos).
 *
 * Os Joy-Cons são pareados por Bluetooth nas configurações do Android. No Android 10+ (driver
 * hid-nintendo do kernel) cada Joy-Con aparece como um InputDevice separado:
 *  - Joy-Con (L)  → controle esquerdo do SteamVR
 *  - Joy-Con (R)  → controle direito do SteamVR
 *  - Pro Controller / grip / gamepad genérico → divide em esquerdo + direito
 *
 * No Android 12+ também lemos o giroscópio do Joy-Con (InputDevice.getSensorManager) para dar
 * orientação aos controles. Segurar o botão Home/Captura por ~1 s recentraliza.
 */
class JoyConManager(context: Context) : InputManager.InputDeviceListener {

    enum class Kind { LEFT, RIGHT, COMBINED }

    class Side {
        @Volatile var connected = false
        @Volatile var buttons = 0
        @Volatile var trigger = 0f
        @Volatile var grip = 0f
        @Volatile var stickX = 0f
        @Volatile var stickY = 0f
        @Volatile var hasImu = false
        @Volatile var orientation = Quat()
        internal var keyTrigger = false
        internal var keyGrip = false
        internal var systemDownAt = 0L
        internal var lastGyroNs = 0L
    }

    private data class Tracked(val device: InputDevice, val kind: Kind, val imuListener: SensorEventListener?)

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val devices = HashMap<Int, Tracked>()
    val left = Side()
    val right = Side()
    var enabled = true
    var onChanged: (() -> Unit)? = null

    init {
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        inputManager.inputDeviceIds.forEach { onInputDeviceAdded(it) }
    }

    fun describe(): String {
        if (devices.isEmpty()) return "Nenhum Joy-Con conectado"
        return devices.values.joinToString(", ") {
            val tag = when (it.kind) { Kind.LEFT -> "E"; Kind.RIGHT -> "D"; Kind.COMBINED -> "E+D" }
            "${it.device.name} [$tag${if (it.imuListener != null) ", giroscópio" else ""}]"
        }
    }

    // ---- descoberta de dispositivos ----

    override fun onInputDeviceAdded(deviceId: Int) {
        val dev = InputDevice.getDevice(deviceId) ?: return
        val isPad = dev.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            dev.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!isPad || dev.isVirtual) return
        val kind = classify(dev)
        val imu = registerImu(dev, kind)
        devices[deviceId] = Tracked(dev, kind, imu)
        sidesOf(kind).forEach { it.connected = true; it.hasImu = imu != null }
        Log.i(TAG, "Controle conectado: ${dev.name} vid=${dev.vendorId.toString(16)} pid=${dev.productId.toString(16)} -> $kind")
        onChanged?.invoke()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val t = devices.remove(deviceId) ?: return
        if (t.imuListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            t.device.sensorManager.unregisterListener(t.imuListener)
        }
        // Mantém o lado conectado se outro dispositivo ainda o cobre.
        val stillLeft = devices.values.any { it.kind != Kind.RIGHT }
        val stillRight = devices.values.any { it.kind != Kind.LEFT }
        if (!stillLeft) left.reset()
        if (!stillRight) right.reset()
        onChanged?.invoke()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        onInputDeviceRemoved(deviceId)
        onInputDeviceAdded(deviceId)
    }

    private fun Side.reset() {
        connected = false; buttons = 0; trigger = 0f; grip = 0f; stickX = 0f; stickY = 0f; hasImu = false
    }

    private fun classify(dev: InputDevice): Kind {
        val name = dev.name.lowercase()
        if (dev.vendorId == NINTENDO_VID) {
            when (dev.productId) {
                PID_JOYCON_L -> return Kind.LEFT
                PID_JOYCON_R -> return Kind.RIGHT
            }
        }
        return when {
            "joy-con (l)" in name || "joycon (l)" in name || "joy-con l" in name -> Kind.LEFT
            "joy-con (r)" in name || "joycon (r)" in name || "joy-con r" in name -> Kind.RIGHT
            else -> Kind.COMBINED
        }
    }

    private fun sidesOf(kind: Kind) = when (kind) {
        Kind.LEFT -> listOf(left)
        Kind.RIGHT -> listOf(right)
        Kind.COMBINED -> listOf(left, right)
    }

    // ---- IMU (Android 12+) ----

    private fun registerImu(dev: InputDevice, kind: Kind): SensorEventListener? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || kind == Kind.COMBINED) return null
        val sm = dev.sensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
        val side = if (kind == Kind.LEFT) left else right
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = integrateGyro(side, e)
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        return listener
    }

    /**
     * Integra a velocidade angular do Joy-Con. Os eixos do sensor de um Joy-Con seguro na vertical
     * (trilho para dentro) são convertidos para o frame do controle OpenVR (-Z = para frente).
     */
    private fun integrateGyro(side: Side, e: SensorEvent) {
        if (side.lastGyroNs == 0L) { side.lastGyroNs = e.timestamp; return }
        val dt = ((e.timestamp - side.lastGyroNs) * 1e-9f).coerceIn(0f, 0.05f)
        side.lastGyroNs = e.timestamp
        // Joy-Con: X = ao longo do controle (para cima), Y = lateral, Z = para fora do botão.
        val wx = -e.values[1]   // pitch
        val wy = e.values[2]    // yaw
        val wz = -e.values[0]   // roll
        val mag = sqrt(wx * wx + wy * wy + wz * wz)
        if (mag < 1e-4f) return
        val dq = Quat.axisAngle(wx / mag, wy / mag, wz / mag, mag * dt)
        side.orientation = (side.orientation * dq).normalized()
    }

    fun recenter() {
        left.orientation = Quat(); right.orientation = Quat()
    }

    // ---- eventos de entrada (chamados pela Activity) ----

    fun handleKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        val t = devices[event.deviceId] ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val (side, bit) = mapKey(t.kind, event.keyCode) ?: return false
        when (bit) {
            BIT_TRIGGER -> { side.keyTrigger = down; side.trigger = if (down) 1f else 0f }
            BIT_GRIP -> { side.keyGrip = down; side.grip = if (down) 1f else 0f }
            else -> side.buttons = if (down) side.buttons or bit else side.buttons and bit.inv()
        }
        if (bit == BIT_TRIGGER) side.setBit(Buttons.TRIGGER_CLICK, down)
        if (bit == BIT_GRIP) side.setBit(Buttons.GRIP_CLICK, down)
        if (bit == Buttons.SYSTEM) {
            if (down && event.repeatCount == 0) side.systemDownAt = SystemClock.uptimeMillis()
            if (!down && SystemClock.uptimeMillis() - side.systemDownAt > 800) recenter()
        }
        return true
    }

    private fun Side.setBit(bit: Int, on: Boolean) {
        buttons = if (on) buttons or bit else buttons and bit.inv()
    }

    private fun mapKey(kind: Kind, code: Int): Pair<Side, Int>? {
        val leftMap = when (code) {
            KeyEvent.KEYCODE_BUTTON_L2 -> BIT_TRIGGER
            KeyEvent.KEYCODE_BUTTON_L1 -> BIT_GRIP
            KeyEvent.KEYCODE_BUTTON_SELECT -> Buttons.MENU
            KeyEvent.KEYCODE_BUTTON_THUMBL -> Buttons.STICK_CLICK
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> Buttons.A
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> Buttons.B
            KeyEvent.KEYCODE_BUTTON_Z -> Buttons.SYSTEM // Captura
            else -> null
        }
        val rightMap = when (code) {
            KeyEvent.KEYCODE_BUTTON_R2 -> BIT_TRIGGER
            KeyEvent.KEYCODE_BUTTON_R1 -> BIT_GRIP
            KeyEvent.KEYCODE_BUTTON_START -> Buttons.MENU
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Buttons.STICK_CLICK
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X -> Buttons.A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y -> Buttons.B
            KeyEvent.KEYCODE_BUTTON_MODE -> Buttons.SYSTEM // Home
            else -> null
        }
        return when (kind) {
            // Joy-Con avulso: alguns kernels reportam o direcional/gatilhos com códigos do outro lado.
            Kind.LEFT -> (leftMap ?: rightMap)?.let { left to it }
            Kind.RIGHT -> (rightMap ?: leftMap)?.let { right to it }
            Kind.COMBINED -> leftMap?.let { left to it } ?: rightMap?.let { right to it }
        }
    }

    fun handleMotion(event: MotionEvent): Boolean {
        if (!enabled) return false
        val t = devices[event.deviceId] ?: return false
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        val lx = axis(event, MotionEvent.AXIS_X); val ly = axis(event, MotionEvent.AXIS_Y)
        var rx = axis(event, MotionEvent.AXIS_Z); var ry = axis(event, MotionEvent.AXIS_RZ)
        val rx2 = axis(event, MotionEvent.AXIS_RX); val ry2 = axis(event, MotionEvent.AXIS_RY)
        if (abs(rx2) + abs(ry2) > abs(rx) + abs(ry)) { rx = rx2; ry = ry2 }
        val lt = maxOf(axis(event, MotionEvent.AXIS_LTRIGGER), axis(event, MotionEvent.AXIS_BRAKE))
        val rt = maxOf(axis(event, MotionEvent.AXIS_RTRIGGER), axis(event, MotionEvent.AXIS_GAS))

        fun apply(side: Side, sx: Float, sy: Float, trig: Float) {
            side.stickX = sx
            side.stickY = -sy // Android: +Y para baixo; OpenVR: +Y para cima
            if (!side.keyTrigger) side.trigger = trig
        }
        when (t.kind) {
            Kind.LEFT -> apply(left, pick(lx, rx), pick(ly, ry), lt)
            Kind.RIGHT -> apply(right, pick(rx, lx), pick(ry, ly), rt)
            Kind.COMBINED -> { apply(left, lx, ly, lt); apply(right, rx, ry, rt) }
        }
        return true
    }

    private fun pick(primary: Float, fallback: Float) = if (abs(primary) >= abs(fallback)) primary else fallback

    private fun axis(e: MotionEvent, a: Int): Float {
        val range = e.device?.getMotionRange(a, e.source) ?: return 0f
        val v = e.getAxisValue(a)
        return if (abs(v) > range.flat) v else 0f
    }

    // ---- háptico ----

    fun vibrate(controller: Int, durationSec: Float, amplitude: Float) {
        val wantLeft = controller == 0
        val target = devices.values.firstOrNull {
            it.kind == Kind.COMBINED || (wantLeft && it.kind == Kind.LEFT) || (!wantLeft && it.kind == Kind.RIGHT)
        } ?: return
        @Suppress("DEPRECATION")
        val vib = target.device.vibrator
        if (!vib.hasVibrator()) return
        val ms = (durationSec * 1000).toLong().coerceIn(10, 1000)
        val amp = (amplitude.coerceIn(0f, 1f) * 255).toInt().coerceIn(1, 255)
        vib.vibrate(VibrationEffect.createOneShot(ms, amp))
    }

    companion object {
        private const val TAG = "JoyCon"
        const val NINTENDO_VID = 0x057E
        const val PID_JOYCON_L = 0x2006
        const val PID_JOYCON_R = 0x2007
        private const val BIT_TRIGGER = 1 shl 30
        private const val BIT_GRIP = 1 shl 29
    }
}
