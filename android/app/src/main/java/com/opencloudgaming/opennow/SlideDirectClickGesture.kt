package com.opencloudgaming.opennow

import kotlin.math.abs

/**
 * Output action produced by [SlideDirectClickGestureDetector].
 */
sealed class SlideDirectClickAction {
    /**
     * Relative mouse movement delta (dx, dy) in pixels.
     */
    data class MouseMove(val dx: Float, val dy: Float, val eventTimeMs: Long) : SlideDirectClickAction()

    /**
     * Direct click triggered at specific screen coordinates (x, y).
     * The host cursor jumps directly to the target point and triggers a left click.
     */
    data class DirectClick(val x: Float, val y: Float, val eventTimeMs: Long) : SlideDirectClickAction()
}

/**
 * Hybrid gesture detector: Slide (relative trackpad move) + Quick Double-Tap (Direct Click).
 *
 * Rules:
 * - Single tap: does not trigger any click (prevents accidental clicks).
 * - Drag/slide: produces [SlideDirectClickAction.MouseMove] relative deltas to move the host cursor.
 * - Quick double tap (second tap down within [doubleTapTimeoutMs] and within [doubleTapSlopPx]):
 *   produces [SlideDirectClickAction.DirectClick] at the second tap's coordinates.
 * - Slow tap / double tap exceeding [doubleTapTimeoutMs] or slop: treated as a normal slide or ignored tap,
 *   no direct click produced.
 */
class SlideDirectClickGestureDetector(
    val doubleTapTimeoutMs: Long = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    val doubleTapSlopPx: Float = DEFAULT_DOUBLE_TAP_SLOP_PX,
    val dragStartSlopPx: Float = DEFAULT_DRAG_START_SLOP_PX,
) {
    companion object {
        const val DEFAULT_DOUBLE_TAP_TIMEOUT_MS = 320L
        const val DEFAULT_DOUBLE_TAP_SLOP_DP = 48f
        const val DEFAULT_DOUBLE_TAP_SLOP_PX = 96f // 48dp on 2x display
        const val DEFAULT_DRAG_START_SLOP_PX = 8f

        fun withDensity(density: Float): SlideDirectClickGestureDetector {
            val slop = (DEFAULT_DOUBLE_TAP_SLOP_DP * density).coerceAtLeast(16f)
            return SlideDirectClickGestureDetector(
                doubleTapTimeoutMs = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
                doubleTapSlopPx = slop,
                dragStartSlopPx = 4f * density,
            )
        }
    }

    private var activePointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var hasMovedPastSlop = false

    private var lastTapTimeMs = Long.MIN_VALUE
    private var lastTapX = Float.NaN
    private var lastTapY = Float.NaN

    fun reset() {
        activePointerId = -1
        hasMovedPastSlop = false
        lastTapTimeMs = Long.MIN_VALUE
        lastTapX = Float.NaN
        lastTapY = Float.NaN
    }

    /**
     * Handles a touch down event.
     * Returns a [SlideDirectClickAction.DirectClick] if this touch qualifies as a quick double tap,
     * or null otherwise.
     */
    fun onTouchDown(pointerId: Int, x: Float, y: Float, eventTimeMs: Long): SlideDirectClickAction? {
        activePointerId = pointerId
        downX = x
        downY = y
        downTimeMs = eventTimeMs
        lastX = x
        lastY = y
        hasMovedPastSlop = false

        val isQuickDoubleTap = (lastTapTimeMs != Long.MIN_VALUE) &&
            (eventTimeMs - lastTapTimeMs <= doubleTapTimeoutMs) &&
            lastTapX.isFinite() && lastTapY.isFinite() &&
            (abs(x - lastTapX) <= doubleTapSlopPx) &&
            (abs(y - lastTapY) <= doubleTapSlopPx)

        if (isQuickDoubleTap) {
            // Reset tap state so 3rd tap does not chain into another double-tap
            lastTapTimeMs = Long.MIN_VALUE
            lastTapX = Float.NaN
            lastTapY = Float.NaN
            return SlideDirectClickAction.DirectClick(x, y, eventTimeMs)
        }

        return null
    }

    /**
     * Handles a touch move event.
     * Returns a [SlideDirectClickAction.MouseMove] if relative movement occurred, or null.
     */
    fun onTouchMove(pointerId: Int, x: Float, y: Float, eventTimeMs: Long): SlideDirectClickAction? {
        if (pointerId != activePointerId) return null
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y

        if (!hasMovedPastSlop) {
            if (abs(x - downX) > dragStartSlopPx || abs(y - downY) > dragStartSlopPx) {
                hasMovedPastSlop = true
            }
        }

        if (dx != 0f || dy != 0f) {
            return SlideDirectClickAction.MouseMove(dx, dy, eventTimeMs)
        }
        return null
    }

    /**
     * Handles a touch up event.
     * Updates previous tap record if this was a stationary tap without drag.
     */
    fun onTouchUp(pointerId: Int, x: Float, y: Float, eventTimeMs: Long) {
        if (pointerId != activePointerId) return
        val dist = maxOf(abs(x - downX), abs(y - downY))
        if (!hasMovedPastSlop && dist <= doubleTapSlopPx) {
            lastTapTimeMs = eventTimeMs
            lastTapX = x
            lastTapY = y
        } else {
            lastTapTimeMs = Long.MIN_VALUE
            lastTapX = Float.NaN
            lastTapY = Float.NaN
        }
        activePointerId = -1
        hasMovedPastSlop = false
    }

    fun onTouchCancel() {
        reset()
    }
}
