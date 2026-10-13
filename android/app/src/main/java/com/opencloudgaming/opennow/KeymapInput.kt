package com.opencloudgaming.opennow

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 1.0.60: Translates physical keyboard/mouse events into virtual gamepad state.
 *
 * For games (e.g. NTE on GFN) that ignore keyboard/mouse input but accept gamepad:
 * - WASD -> left stick, mouse movement -> right stick (camera)
 * - LMB -> right trigger (attack), RMB -> left trigger (aim), MMB -> right thumb
 * - Space=A, E=X, Q=Y, F=B, R=RB, Shift=LB, Ctrl=LT
 * - 1-4=D-pad, Esc=Start, Tab=Back
 *
 * Lives in its own file (not inside Streaming.kt): the FIR data-flow analyzer OOMs
 * the Kotlin daemon once Streaming.kt passes ~7600 lines, even at 5GB heap.
 * Unmapped keys return false so the caller can fall through to the normal path.
 */
internal class KeyboardToGamepadMapper(
    private val scope: CoroutineScope,
    private val onStateChanged: () -> Unit,
) {
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) release()
            NativeInputDiagnostics.add("keyboard-to-gamepad ${if (value) "enabled" else "disabled"}")
            onStateChanged()
        }

    private val heldKeys = mutableSetOf<Int>()
    private var buttons = 0
    private var leftTrigger = 0
    private var rightTrigger = 0
    private var leftStickActive = false
    private var leftStickX = 0
    private var leftStickY = 0
    private var rightStickAccumX = 0f
    private var rightStickAccumY = 0f
    private var mouseDecayJob: Job? = null
    private var mouseLmbHeld = false
    private var mouseRmbHeld = false
    private var mouseMmbHeld = false

    // Exposed for the gamepad pipeline in NativeStreamClient.
    val mappedButtons: Int get() = buttons
    val mappedLeftTrigger: Int get() = leftTrigger
    val mappedRightTrigger: Int get() = rightTrigger
    val mappedLeftStickActive: Boolean get() = leftStickActive
    val mappedLeftStickX: Int get() = leftStickX
    val mappedLeftStickY: Int get() = leftStickY
    val mappedRightStickX: Float get() = rightStickAccumX
    val mappedRightStickY: Float get() = rightStickAccumY

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isMappedKey(event.keyCode)) return false
        // Ignore auto-repeat downs; the held set already covers the key.
        when (event.action) {
            KeyEvent.ACTION_DOWN -> heldKeys.add(event.keyCode)
            KeyEvent.ACTION_UP -> heldKeys.remove(event.keyCode)
            else -> return false
        }
        recompute()
        return true
    }

    fun onMouseMove(dx: Float, dy: Float) {
        rightStickAccumX = (rightStickAccumX + dx / MOUSE_SENSITIVITY_PX).coerceIn(-1f, 1f)
        rightStickAccumY = (rightStickAccumY + dy / MOUSE_SENSITIVITY_PX).coerceIn(-1f, 1f)
        onStateChanged()
        // Recenter the stick shortly after the mouse stops, like releasing a physical stick.
        mouseDecayJob?.cancel()
        mouseDecayJob = scope.launch {
            delay(MOUSE_DECAY_MS)
            rightStickAccumX = 0f
            rightStickAccumY = 0f
            onStateChanged()
        }
    }

    /** GFN mouse button ids: 1=left, 2=middle, 3=right. */
    fun onMouseButton(gfnButton: Int, pressed: Boolean) {
        when (gfnButton) {
            1 -> mouseLmbHeld = pressed
            2 -> mouseMmbHeld = pressed
            3 -> mouseRmbHeld = pressed
        }
        recompute()
    }

    fun release() {
        heldKeys.clear()
        releaseMouseButtons()
        buttons = 0
        leftTrigger = 0
        rightTrigger = 0
        leftStickActive = false
        leftStickX = 0
        leftStickY = 0
        rightStickAccumX = 0f
        rightStickAccumY = 0f
        mouseDecayJob?.cancel()
        mouseDecayJob = null
    }

    fun releaseMouseButtons() {
        mouseLmbHeld = false
        mouseRmbHeld = false
        mouseMmbHeld = false
        recompute()
    }

    private fun recompute() {
        var b = 0
        var stickX = 0f
        var stickY = 0f
        var lt = 0
        for (keyCode in heldKeys) {
            when (keyCode) {
                KeyEvent.KEYCODE_W -> stickY -= 1f
                KeyEvent.KEYCODE_S -> stickY += 1f
                KeyEvent.KEYCODE_A -> stickX -= 1f
                KeyEvent.KEYCODE_D -> stickX += 1f
                KeyEvent.KEYCODE_SPACE -> b = b or GamepadButtonMapping.A
                KeyEvent.KEYCODE_E -> b = b or GamepadButtonMapping.X
                KeyEvent.KEYCODE_Q -> b = b or GamepadButtonMapping.Y
                KeyEvent.KEYCODE_F -> b = b or GamepadButtonMapping.B
                KeyEvent.KEYCODE_R -> b = b or GamepadButtonMapping.RIGHT_SHOULDER
                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT ->
                    b = b or GamepadButtonMapping.LEFT_SHOULDER
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> lt = 255
                KeyEvent.KEYCODE_1 -> b = b or GamepadButtonMapping.DPAD_UP
                KeyEvent.KEYCODE_2 -> b = b or GamepadButtonMapping.DPAD_RIGHT
                KeyEvent.KEYCODE_3 -> b = b or GamepadButtonMapping.DPAD_DOWN
                KeyEvent.KEYCODE_4 -> b = b or GamepadButtonMapping.DPAD_LEFT
                KeyEvent.KEYCODE_ESCAPE -> b = b or GamepadButtonMapping.START
                KeyEvent.KEYCODE_TAB -> b = b or GamepadButtonMapping.BACK
            }
        }
        // Mouse buttons share the triggers with Ctrl.
        if (mouseRmbHeld) lt = 255
        rightTrigger = if (mouseLmbHeld) 255 else 0
        if (mouseMmbHeld) b = b or GamepadButtonMapping.RIGHT_THUMB
        // Normalize diagonals to unit magnitude.
        val mag = sqrt(stickX * stickX + stickY * stickY)
        if (mag > 1f) {
            stickX /= mag
            stickY /= mag
        }
        buttons = b
        leftTrigger = lt
        leftStickActive = stickX != 0f || stickY != 0f
        // Same Y convention as setVirtualLeftStick: screen-down-positive input, negated for the pad.
        leftStickX = normalizeAxis(stickX)
        leftStickY = normalizeAxis(-stickY)
        onStateChanged()
    }

    private fun isMappedKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_F,
        KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
        -> true
        else -> false
    }

    private companion object {
        // Pixels of mouse travel per full right-stick deflection.
        const val MOUSE_SENSITIVITY_PX = 160f
        // Recenters the mapped right stick after this long without mouse movement.
        const val MOUSE_DECAY_MS = 140L

        fun normalizeAxis(value: Float): Int =
            (value.coerceIn(-1f, 1f) * 32767).roundToInt().coerceIn(-32768, 32767)
    }
}
