package com.phonesteam.app.input

import com.phonesteam.app.util.Quat
import com.phonesteam.app.util.Vec3

object Buttons {
    const val SYSTEM = 1 shl 0
    const val MENU = 1 shl 1
    const val A = 1 shl 2
    const val B = 1 shl 3
    const val TRIGGER_CLICK = 1 shl 4
    const val GRIP_CLICK = 1 shl 5
    const val STICK_CLICK = 1 shl 6
}

object ControllerFlags {
    const val CONNECTED = 1
    const val POSE_VALID = 2
    const val HAND_TRACKED = 4
}

enum class InputSource(val code: Int) { NONE(0), JOYCON(1), HAND(2), HYBRID(3) }

/** Estado de um controle virtual (esquerdo ou direito) enviado ao SteamVR. */
data class ControllerState(
    val flags: Int = 0,
    val source: InputSource = InputSource.NONE,
    val position: Vec3 = Vec3(),
    val rotation: Quat = Quat(),
    val buttons: Int = 0,
    val trigger: Float = 0f,
    val grip: Float = 0f,
    val stickX: Float = 0f,
    val stickY: Float = 0f,
) {
    val connected get() = flags and ControllerFlags.CONNECTED != 0
}

/** Snapshot completo da pose do usuário. */
data class XrFrameState(
    val headRotation: Quat,
    val headPosition: Vec3,
    val left: ControllerState,
    val right: ControllerState,
)
