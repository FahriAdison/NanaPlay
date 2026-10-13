package com.opencloudgaming.opennow

import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1.0.60: Translates physical keyboard/mouse into virtual gamepad input, using only
 * the public virtual-gamepad API on NativeStreamClient (setVirtualButton/Stick/Trigger).
 *
 * For games (e.g. NTE on GFN) that ignore keyboard/mouse input but accept gamepad:
 * - WASD -> left stick, mouse movement -> right stick (camera)
 * - LMB -> right trigger (attack), RMB -> left trigger (aim), MMB -> right thumb click
 * - Space=A, E=X, Q=Y, F=B, R=RB, Shift=LB, Ctrl=LT
 * - 1-4=D-pad, Esc=Start, Tab=Back
 *
 * Standalone singleton: MainActivity feeds it raw events before they reach the normal
 * input pipeline. Zero changes to Streaming.kt's internals (its 7535 lines already
 * push the Kotlin FIR data-flow analyzer to OOM; even +62 lines breaks the build).
 */
object KeymapMapper {
    @Volatile
    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                lastClient?.let { releaseAll(it) }
            }
            NativeInputDiagnostics.add("keyboard-to-gamepad ${if (value) "enabled" else "disabled"}")
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val heldKeys = mutableSetOf<Int>()
    private var lastClient: NativeStreamClient? = null
    private var mouseDecayJob: Job? = null
    private var rightStickX = 0f
    private var rightStickY = 0f

    /**
     * Returns true if the key event was consumed as gamepad input.
     * Unmapped keys return false so the caller can fall through.
     */
    fun onKeyEvent(client: NativeStreamClient, event: KeyEvent): Boolean {
        lastClient = client
        val mapped = mappedButton(event.keyCode)
        val isStickKey = event.keyCode == KeyEvent.KEYCODE_W ||
            event.keyCode == KeyEvent.KEYCODE_A ||
            event.keyCode == KeyEvent.KEYCODE_S ||
            event.keyCode == KeyEvent.KEYCODE_D
        if (mapped == 0 && !isStickKey) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Ignore auto-repeat; the held set already covers the key.
                if (!heldKeys.add(event.keyCode)) return true
            }
            KeyEvent.ACTION_UP -> {
                heldKeys.remove(event.keyCode)
            }
            else -> return false
        }
        if (mapped != 0) {
            client.setVirtualButton(mapped, event.action == KeyEvent.ACTION_DOWN)
        }
        if (isStickKey) updateLeftStick(client)
        // Ctrl doubles as left trigger.
        if (event.keyCode == KeyEvent.KEYCODE_CTRL_LEFT || event.keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
            client.setVirtualTrigger(left = true, pressed = event.action == KeyEvent.ACTION_DOWN)
        }
        return true
    }

    fun onMouseMove(client: NativeStreamClient, dx: Float, dy: Float) {
        lastClient = client
        rightStickX = (rightStickX + dx / MOUSE_SENSITIVITY_PX).coerceIn(-1f, 1f)
        rightStickY = (rightStickY + dy / MOUSE_SENSITIVITY_PX).coerceIn(-1f, 1f)
        client.setVirtualRightStick(rightStickX, rightStickY)
        // Recenter shortly after the mouse stops, like releasing a physical stick.
        mouseDecayJob?.cancel()
        mouseDecayJob = scope.launch {
            delay(MOUSE_DECAY_MS)
            rightStickX = 0f
            rightStickY = 0f
            client.setVirtualRightStick(0f, 0f)
        }
    }

    /** GFN mouse button ids: 1=left, 2=middle, 3=right. */
    fun onMouseButton(client: NativeStreamClient, gfnButton: Int, pressed: Boolean) {
        lastClient = client
        when (gfnButton) {
            1 -> client.setVirtualTrigger(left = false, pressed = pressed) // LMB = RT attack
            3 -> client.setVirtualTrigger(left = true, pressed = pressed) // RMB = LT aim
            2 -> client.setVirtualButton(GamepadButtonMapping.RIGHT_THUMB, pressed)
        }
    }

    fun releaseAll(client: NativeStreamClient) {
        for (keyCode in heldKeys.toList()) {
            val mapped = mappedButton(keyCode)
            if (mapped != 0) client.setVirtualButton(mapped, false)
        }
        heldKeys.clear()
        releaseMouseButtonState()
        client.setVirtualTrigger(left = true, pressed = false)
        client.setVirtualTrigger(left = false, pressed = false)
        client.setVirtualLeftStick(0f, 0f)
        rightStickX = 0f
        rightStickY = 0f
        mouseDecayJob?.cancel()
        mouseDecayJob = null
        client.setVirtualRightStick(0f, 0f)
    }

    private fun updateLeftStick(client: NativeStreamClient) {
        var x = 0f
        var y = 0f
        if (heldKeys.contains(KeyEvent.KEYCODE_W)) y -= 1f
        if (heldKeys.contains(KeyEvent.KEYCODE_S)) y += 1f
        if (heldKeys.contains(KeyEvent.KEYCODE_A)) x -= 1f
        if (heldKeys.contains(KeyEvent.KEYCODE_D)) x += 1f
        // Normalize diagonals.
        val mag = kotlin.math.sqrt(x * x + y * y)
        if (mag > 1f) {
            x /= mag
            y /= mag
        }
        client.setVirtualLeftStick(x, y)
    }

    private fun mappedButton(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_SPACE -> GamepadButtonMapping.A
        KeyEvent.KEYCODE_E -> GamepadButtonMapping.X
        KeyEvent.KEYCODE_Q -> GamepadButtonMapping.Y
        KeyEvent.KEYCODE_F -> GamepadButtonMapping.B
        KeyEvent.KEYCODE_R -> GamepadButtonMapping.RIGHT_SHOULDER
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> GamepadButtonMapping.LEFT_SHOULDER
        KeyEvent.KEYCODE_1 -> GamepadButtonMapping.DPAD_UP
        KeyEvent.KEYCODE_2 -> GamepadButtonMapping.DPAD_RIGHT
        KeyEvent.KEYCODE_3 -> GamepadButtonMapping.DPAD_DOWN
        KeyEvent.KEYCODE_4 -> GamepadButtonMapping.DPAD_LEFT
        KeyEvent.KEYCODE_ESCAPE -> GamepadButtonMapping.START
        KeyEvent.KEYCODE_TAB -> GamepadButtonMapping.BACK
        else -> 0
    }

    private const val MOUSE_SENSITIVITY_PX = 160f
    private const val MOUSE_DECAY_MS = 140L

    /** Mirrors NativeStreamClient's hardware-keyboard detection. */
    fun isHardwareKeyboardEvent(event: KeyEvent): Boolean =
        !AndroidControllerInput.isControllerEvent(event.source, event.deviceId) &&
            ((event.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD ||
                InputDevice.getDevice(event.deviceId)?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC)

    /** Mirrors NativeStreamClient's mouse detection. */
    fun isMouseEvent(event: android.view.MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

    fun releaseMouseButtons(client: NativeStreamClient) {
        releaseMouseButtonState()
        client.setVirtualTrigger(left = true, pressed = false)
        client.setVirtualTrigger(left = false, pressed = false)
    }

    /** GFN mouse button ids, mirroring NativeStreamClient.toGfnMouseButton. */
    fun Int.toKeymapGfnButton(): Int = when {
        this and android.view.MotionEvent.BUTTON_PRIMARY != 0 -> 1
        this and android.view.MotionEvent.BUTTON_TERTIARY != 0 -> 2
        this and android.view.MotionEvent.BUTTON_SECONDARY != 0 -> 3
        else -> 1
    }

    private fun releaseMouseButtonState() {
        mouseLmbHeld = false
        mouseRmbHeld = false
        mouseMmbHeld = false
    }
}
