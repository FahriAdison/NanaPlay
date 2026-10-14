package com.papahchan.nanaplay.keymap

import kotlin.math.sqrt

/**
 * 1.0.60: Pure-Kotlin keyboard/mouse → gamepad mapping.
 *
 * Lives in its own Gradle module so the Kotlin FIR data-flow analyzer processes it
 * separately from the :app module (which is at the analyzer's critical threshold —
 * any addition to :app triggers exponential memory blowup and OOM).
 *
 * No Android framework dependencies: callers pass primitive key codes and actions.
 * Gamepad output goes through [GamepadOutput], implemented by the app module.
 *
 * Mapping (for games like NTE on GFN that ignore keyboard/mouse but accept gamepad):
 * - WASD -> left stick, mouse movement -> right stick (camera)
 * - LMB -> right trigger (attack), RMB -> left trigger (aim), MMB -> right thumb click
 * - Space=A, E=X, Q=Y, F=B, R=RB, Shift=LB, Ctrl=LT
 * - 1-4=D-pad, Esc=Start, Tab=Back
 */
interface GamepadOutput {
    fun setButton(mask: Int, pressed: Boolean)
    fun setLeftStick(x: Float, y: Float)
    fun setRightStick(x: Float, y: Float)
    fun setTrigger(left: Boolean, pressed: Boolean)
}

/** Gamepad button bit masks, mirroring the app's GamepadButtonMapping. */
object KeymapButtons {
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val GUIDE = 0x0400
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
}

object KeymapMapper {
    @Volatile
    var active: Boolean = false

    private val heldKeys = mutableSetOf<Int>()
    private val heldMouseButtons = mutableSetOf<Int>()
    private var rightStickX = 0f
    // F-23: auto-decay — recenter the right stick shortly after the mouse
    // stops moving, otherwise the camera keeps spinning on the last delta.
    private val decayExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "keymap-mouse-decay").apply { isDaemon = true }
    }
    private var decayFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var rightStickY = 0f
    private var lastOutput: GamepadOutput? = null

    // Android KeyEvent key codes (hardcoded to avoid android.view dependency).
    private const val KEYCODE_W = 51
    private const val KEYCODE_A = 29
    private const val KEYCODE_S = 47
    private const val KEYCODE_D = 32
    private const val KEYCODE_SPACE = 62
    private const val KEYCODE_E = 33
    private const val KEYCODE_Q = 45
    private const val KEYCODE_F = 34
    private const val KEYCODE_R = 46
    private const val KEYCODE_SHIFT_LEFT = 59
    private const val KEYCODE_SHIFT_RIGHT = 60
    private const val KEYCODE_CTRL_LEFT = 113
    private const val KEYCODE_CTRL_RIGHT = 114
    private const val KEYCODE_1 = 8
    private const val KEYCODE_2 = 9
    private const val KEYCODE_3 = 10
    private const val KEYCODE_4 = 11
    private const val KEYCODE_ESCAPE = 111
    private const val KEYCODE_TAB = 61
    private const val KEYCODE_C = 31
    private const val KEYCODE_M = 41
    private const val KEYCODE_ALT_LEFT = 57
    private const val KEYCODE_ALT_RIGHT = 58

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    /**
     * Returns true if the key was consumed as gamepad input.
     * Unmapped keys return false so the caller can fall through.
     */
    fun onKeyEvent(output: GamepadOutput, keyCode: Int, action: Int): Boolean {
        // Alt held = bypass mapper (show cursor for menu clicks)
        if (keyCode == KEYCODE_ALT_LEFT || keyCode == KEYCODE_ALT_RIGHT) {
            if (action == ACTION_DOWN) heldKeys.add(keyCode) else heldKeys.remove(keyCode)
            return false
        }
        if (heldKeys.contains(KEYCODE_ALT_LEFT) || heldKeys.contains(KEYCODE_ALT_RIGHT)) return false
        val mapped = mappedButton(keyCode)
        val isStickKey = keyCode == KEYCODE_W || keyCode == KEYCODE_A ||
            keyCode == KEYCODE_S || keyCode == KEYCODE_D
        // F-22: Ctrl is handled as a trigger below, not via mappedButton —
        // don't let the guard swallow it.
        val isTriggerKey = keyCode == KEYCODE_CTRL_LEFT || keyCode == KEYCODE_CTRL_RIGHT
        if (mapped == 0 && !isStickKey && !isTriggerKey) return false
        lastOutput = output
        when (action) {
            ACTION_DOWN -> if (!heldKeys.add(keyCode)) return true // ignore auto-repeat
            ACTION_UP -> heldKeys.remove(keyCode)
            else -> return false
        }
        if (mapped != 0) {
            output.setButton(mapped, action == ACTION_DOWN)
        }
        if (isStickKey) updateLeftStick(output)
        if (keyCode == KEYCODE_CTRL_LEFT || keyCode == KEYCODE_CTRL_RIGHT) {
            output.setButton(KeymapButtons.LEFT_THUMB, action == ACTION_DOWN)
        }
        return true
    }

    @Synchronized
    fun onMouseMove(output: GamepadOutput, dx: Float, dy: Float) {
        lastOutput = output
        rightStickX = (rightStickX + dx / MOUSE_SENSITIVITY_PX).coerceIn(-1f, 1f)
        rightStickY = (rightStickY + dy / MOUSE_SENSITIVITY_PX).coerceIn(-1f, 1f)
        output.setRightStick(rightStickX, rightStickY)
        // F-23: re-arm the decay timer on every move.
        decayFuture?.cancel(false)
        decayFuture = decayExecutor.schedule({ onMouseDecay(output) }, 45L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun onMouseDecay(output: GamepadOutput) {
        rightStickX = 0f
        rightStickY = 0f
        output.setRightStick(0f, 0f)
    }

    /** GFN mouse button ids: 1=left, 2=middle, 3=right. */
    fun onMouseButton(output: GamepadOutput, gfnButton: Int, pressed: Boolean) {
        lastOutput = output
        // F-25: track held buttons so a release that happens mid-drag can be
        // reconciled via syncMouseButtons() instead of sticking forever.
        if (pressed) heldMouseButtons.add(gfnButton) else heldMouseButtons.remove(gfnButton)
        when (gfnButton) {
            1 -> output.setTrigger(left = false, pressed = pressed) // LMB = RT attack
            3 -> output.setTrigger(left = true, pressed = pressed) // RMB = LT aim
            2 -> output.setButton(KeymapButtons.RIGHT_THUMB, pressed)
        }
    }

    fun releaseMouseButtons(output: GamepadOutput) {
        heldMouseButtons.clear()
        output.setTrigger(left = true, pressed = false)
        output.setTrigger(left = false, pressed = false)
    }

    /**
     * F-25: reconcile held mouse buttons against the live MotionEvent buttonState.
     * Call on every MOVE/HOVER_MOVE. Pure booleans — no android.view dependency.
     */
    fun syncMouseButtons(
        output: GamepadOutput,
        primaryHeld: Boolean,
        secondaryHeld: Boolean,
        tertiaryHeld: Boolean,
    ) {
        if (!primaryHeld && heldMouseButtons.remove(1)) output.setTrigger(left = false, pressed = false)
        if (!secondaryHeld && heldMouseButtons.remove(3)) output.setTrigger(left = true, pressed = false)
        if (!tertiaryHeld && heldMouseButtons.remove(2)) output.setButton(KeymapButtons.RIGHT_THUMB, false)
    }

    fun releaseAll(output: GamepadOutput) {
        for (keyCode in heldKeys.toList()) {
            val mapped = mappedButton(keyCode)
            if (mapped != 0) output.setButton(mapped, false)
        }
        heldKeys.clear()
        releaseMouseButtons(output)
        output.setLeftStick(0f, 0f)
        rightStickX = 0f
        rightStickY = 0f
        output.setRightStick(0f, 0f)
        lastOutput = null
    }

    /** Called when [active] is set to false; releases via the last known output. */
    fun onDeactivated() {
        lastOutput?.let { releaseAll(it) }
    }

    private fun updateLeftStick(output: GamepadOutput) {
        var x = 0f
        var y = 0f
        if (heldKeys.contains(KEYCODE_W)) y -= 1f
        if (heldKeys.contains(KEYCODE_S)) y += 1f
        if (heldKeys.contains(KEYCODE_A)) x -= 1f
        if (heldKeys.contains(KEYCODE_D)) x += 1f
        val mag = sqrt(x * x + y * y)
        if (mag > 1f) {
            x /= mag
            y /= mag
        }
        output.setLeftStick(x, y)
    }

    private fun mappedButton(keyCode: Int): Int = when (keyCode) {
        KEYCODE_SPACE -> KeymapButtons.A
        KEYCODE_E -> KeymapButtons.X
        KEYCODE_Q -> KeymapButtons.Y
        KEYCODE_F -> KeymapButtons.B
        KEYCODE_SHIFT_LEFT, KEYCODE_SHIFT_RIGHT -> KeymapButtons.RIGHT_SHOULDER
        KEYCODE_1 -> KeymapButtons.DPAD_UP
        KEYCODE_2 -> KeymapButtons.DPAD_RIGHT
        KEYCODE_3 -> KeymapButtons.DPAD_DOWN
        KEYCODE_4 -> KeymapButtons.DPAD_LEFT
        KEYCODE_ESCAPE -> KeymapButtons.START
        KEYCODE_TAB -> KeymapButtons.LEFT_SHOULDER
        KEYCODE_C -> KeymapButtons.LEFT_THUMB
        KEYCODE_M -> KeymapButtons.BACK
        else -> 0
    }

    private const val MOUSE_SENSITIVITY_PX = 160f
}
