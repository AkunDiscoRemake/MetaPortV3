package com.phonesteam.app.vr

import com.phonesteam.app.hand.HandTracker
import com.phonesteam.app.input.Buttons
import com.phonesteam.app.input.ControllerFlags
import com.phonesteam.app.input.ControllerState
import com.phonesteam.app.input.InputSource
import com.phonesteam.app.input.JoyConManager
import com.phonesteam.app.input.XrFrameState
import com.phonesteam.app.util.Quat
import com.phonesteam.app.util.Vec3

/**
 * Junta cabeça + Joy-Cons + mãos rastreadas em um [XrFrameState].
 *
 * Por lado:
 *  - mão visível  → posição (e orientação, se o Joy-Con não tiver giroscópio) vêm da câmera
 *  - Joy-Con      → botões, analógico, gatilhos e orientação pelo giroscópio
 *  - só Joy-Con   → posição por "modelo de braço" à frente do corpo
 */
class InputFusion(
    private val head: HeadTracker,
    private val joyCons: JoyConManager?,
    private val hands: HandTracker?,
) {
    /** Botão do Cardboard (toque na tela) vira o gatilho do controle direito quando não há outro. */
    @Volatile var screenTouch = false

    fun frame(): XrFrameState {
        val rot = head.orientation()
        val pos = head.position(rot)
        return XrFrameState(
            rot, pos,
            side(0, rot, pos, joyCons?.left, hands?.fresh(hands.left)),
            side(1, rot, pos, joyCons?.right, hands?.fresh(hands.right)),
        )
    }

    private fun side(index: Int, headRot: Quat, headPos: Vec3, joy: JoyConManager.Side?, hand: HandTracker.Hand?): ControllerState {
        val joyOn = joy?.connected == true
        val handOn = hand != null
        val touchOn = index == 1 && screenTouch && !joyOn && !handOn
        if (!joyOn && !handOn && !touchOn) return ControllerState()

        val yaw = Quat.axisAngle(0f, 1f, 0f, headRot.yaw())
        val rotation = when {
            joyOn && joy!!.hasImu -> joy.orientation
            handOn -> (headRot * hand!!.rotation).normalized()
            else -> headRot
        }
        val position = if (handOn) {
            headPos + headRot.rotate(hand!!.position)
        } else {
            val shoulder = headPos + yaw.rotate(Vec3(if (index == 0) -0.18f else 0.18f, -0.45f, 0.05f))
            shoulder + rotation.rotate(Vec3(0f, 0f, -0.35f))
        }

        var buttons = joy?.buttons ?: 0
        var trigger = joy?.trigger ?: 0f
        var grip = joy?.grip ?: 0f
        if (hand != null) {
            trigger = maxOf(trigger, hand.trigger)
            grip = maxOf(grip, hand.grip)
            if (hand.a) buttons = buttons or Buttons.A
            if (hand.b) buttons = buttons or Buttons.B
            if (hand.menu) buttons = buttons or Buttons.MENU
        }
        if (touchOn) trigger = 1f
        if (trigger > 0.9f) buttons = buttons or Buttons.TRIGGER_CLICK
        if (grip > 0.9f) buttons = buttons or Buttons.GRIP_CLICK

        var flags = ControllerFlags.CONNECTED or ControllerFlags.POSE_VALID
        if (handOn) flags = flags or ControllerFlags.HAND_TRACKED
        val source = when {
            joyOn && handOn -> InputSource.HYBRID
            joyOn -> InputSource.JOYCON
            handOn -> InputSource.HAND
            else -> InputSource.NONE
        }
        return ControllerState(
            flags, source, position, rotation, buttons, trigger, grip,
            joy?.stickX ?: 0f, joy?.stickY ?: 0f,
        )
    }
}
