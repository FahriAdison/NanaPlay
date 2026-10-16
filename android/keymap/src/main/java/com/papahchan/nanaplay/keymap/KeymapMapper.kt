package com.papahchan.nanaplay.keymap

import kotlin.math.pow
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
    @Synchronized
    fun onKeyEvent(output: GamepadOutput, keyCode: Int, action: Int): Boolean {
        // Argon-2: consume the Alt event itself so it never leaks to the PC
        // as a raw keyboard key. Alt-held = bypass window for menu cursor.
        if (keyCode == KEYCODE_ALT_LEFT || keyCode == KEYCODE_ALT_RIGHT) {
            // A-01: entering the bypass window — recenter the right stick and
            // release mouse buttons so camera deflection / triggers can't stick
            // while the mouse is handed back to the PC as a menu cursor.
            if (action == ACTION_DOWN) {
                onMouseDecay(output)
                releaseMouseButtons(output)
                heldKeys.add(keyCode)
            } else {
                heldKeys.remove(keyCode)
            }
            return true
        }
        // Argon-2: while Alt is held, still honor ACTION_UP for held keys so
        // movement keys can't stick when released during the bypass window.
        if (heldKeys.contains(KEYCODE_ALT_LEFT) || heldKeys.contains(KEYCODE_ALT_RIGHT)) {
            if (action == ACTION_UP && heldKeys.remove(keyCode)) {
                val mapped = mappedButton(keyCode)
                if (mapped != 0) releaseMappedButton(output, mapped)
                if (keyCode == KEYCODE_W || keyCode == KEYCODE_A ||
                    keyCode == KEYCODE_S || keyCode == KEYCODE_D) {
                    updateLeftStick(output)
                }
            }
            return true
        }
        val mapped = mappedButton(keyCode)
        val isStickKey = keyCode == KEYCODE_W || keyCode == KEYCODE_A ||
            keyCode == KEYCODE_S || keyCode == KEYCODE_D
        if (mapped == 0 && !isStickKey) return false
        lastOutput = output
        when (action) {
            ACTION_DOWN -> if (!heldKeys.add(keyCode)) return true // ignore auto-repeat
            ACTION_UP -> heldKeys.remove(keyCode)
            else -> return false
        }
        if (mapped != 0) {
            if (action == ACTION_DOWN) output.setButton(mapped, true)
            else releaseMappedButton(output, mapped)
        }
        if (isStickKey) updateLeftStick(output)
        return true
    }

    /**
     * A-01: true while Alt is held — callers (e.g. Streaming.kt's mouse path)
     * must skip the gamepad mapping so the mouse works as a PC menu cursor.
     */
    @Synchronized
    fun isAltBypassActive(): Boolean =
        heldKeys.contains(KEYCODE_ALT_LEFT) || heldKeys.contains(KEYCODE_ALT_RIGHT)

    /**
     * Argon-2: releases a mapped button. L3 is shared by C and both Ctrls —
     * only release it when none of them is still held.
     * A-03: same guard for RB, shared by R and both Shifts.
     */
    private fun releaseMappedButton(output: GamepadOutput, mapped: Int) {
        if (mapped == KeymapButtons.LEFT_THUMB) {
            val stillHolding = heldKeys.contains(KEYCODE_C) ||
                heldKeys.contains(KEYCODE_CTRL_LEFT) ||
                heldKeys.contains(KEYCODE_CTRL_RIGHT)
            if (!stillHolding) output.setButton(mapped, false)
        } else if (mapped == KeymapButtons.RIGHT_SHOULDER) {
            val stillHolding = heldKeys.contains(KEYCODE_R) ||
                heldKeys.contains(KEYCODE_SHIFT_LEFT) ||
                heldKeys.contains(KEYCODE_SHIFT_RIGHT)
            if (!stillHolding) output.setButton(mapped, false)
        } else {
            output.setButton(mapped, false)
        }
    }

    @Synchronized
    fun onMouseMove(output: GamepadOutput, dx: Float, dy: Float) {
        lastOutput = output
        val magnitude = sqrt(dx * dx + dy * dy)
        // Abaikan noise sub-piksel (< 0.35px) agar anti-deadzone floor tidak memicu drift.
        if (magnitude < 0.35f) return

        val unitX = dx / magnitude
        val unitY = dy / magnitude
        val normalizedSpeed = (magnitude / MAX_MOUSE_DELTA_PX).coerceIn(0f, 1f)
        val curvedSpeed = normalizedSpeed.pow(POWER_EXPONENT)
        val stickDeflection = ANTI_DEADZONE_FLOOR + (1f - ANTI_DEADZONE_FLOOR) * curvedSpeed

        rightStickX = (unitX * stickDeflection).coerceIn(-1f, 1f)
        rightStickY = (unitY * stickDeflection).coerceIn(-1f, 1f)
        output.setRightStick(rightStickX, rightStickY)

        // K-01: 50ms (~3 frame @ 60fps) agar engine UE5 sempat mengintegrasikan rotasi kamera.
        decayFuture?.cancel(false)
        decayFuture = decayExecutor.schedule(
            { onMouseDecay(output) },
            MOUSE_DECAY_TIMEOUT_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    fun onMouseDecay(output: GamepadOutput) {
        rightStickX = 0f
        rightStickY = 0f
        output.setRightStick(0f, 0f)
    }

    /** GFN mouse button ids: 1=left, 2=middle, 3=right. */
    @Synchronized
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
        // Argon-2: R3 (middle click) was never released here — it stuck forever.
        output.setButton(KeymapButtons.RIGHT_THUMB, false)
    }

    /**
     * F-25: reconcile held mouse buttons against the live MotionEvent buttonState.
     * Call on every MOVE/HOVER_MOVE. Pure booleans — no android.view dependency.
     */
    @Synchronized
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

    @Synchronized
    fun releaseAll(output: GamepadOutput) {
        // A-02: stop the decay thread from firing after teardown.
        decayFuture?.cancel(false)
        decayFuture = null
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
        // A-03: R was dropped from the map in v1.0.63–v1.0.66 — restore it on RB.
        KEYCODE_R -> KeymapButtons.RIGHT_SHOULDER
        KEYCODE_SHIFT_LEFT, KEYCODE_SHIFT_RIGHT -> KeymapButtons.RIGHT_SHOULDER
        KEYCODE_1 -> KeymapButtons.DPAD_UP
        KEYCODE_2 -> KeymapButtons.DPAD_RIGHT
        KEYCODE_3 -> KeymapButtons.DPAD_DOWN
        KEYCODE_4 -> KeymapButtons.DPAD_LEFT
        KEYCODE_ESCAPE -> KeymapButtons.START
        KEYCODE_TAB -> KeymapButtons.LEFT_SHOULDER
        KEYCODE_C, KEYCODE_CTRL_LEFT, KEYCODE_CTRL_RIGHT -> KeymapButtons.LEFT_THUMB
        KEYCODE_M -> KeymapButtons.BACK
        else -> 0
    }

    private const val ANTI_DEADZONE_FLOOR = 0.20f
    private const val MAX_MOUSE_DELTA_PX = 45f
    private const val POWER_EXPONENT = 0.75f
    private const val MOUSE_DECAY_TIMEOUT_MS = 50L
}
