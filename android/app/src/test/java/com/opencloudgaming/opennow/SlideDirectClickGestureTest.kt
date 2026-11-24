package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideDirectClickGestureTest {

    @Test
    fun singleTapDoesNotProduceClick() {
        val detector = SlideDirectClickGestureDetector(
            doubleTapTimeoutMs = 320L,
            doubleTapSlopPx = 96f,
        )

        val downAction = detector.onTouchDown(pointerId = 0, x = 150f, y = 200f, eventTimeMs = 1000L)
        assertNull(downAction)

        detector.onTouchUp(pointerId = 0, x = 150f, y = 200f, eventTimeMs = 1080L)
        // No click action should be emitted on down or up
    }

    @Test
    fun slideDragProducesOnlyMouseMoveDeltas() {
        val detector = SlideDirectClickGestureDetector(
            doubleTapTimeoutMs = 320L,
            doubleTapSlopPx = 96f,
        )

        val downAction = detector.onTouchDown(pointerId = 1, x = 100f, y = 100f, eventTimeMs = 2000L)
        assertNull(downAction)

        val moveAction1 = detector.onTouchMove(pointerId = 1, x = 115f, y = 105f, eventTimeMs = 2016L)
        assertNotNull(moveAction1)
        assertTrue(moveAction1 is SlideDirectClickAction.MouseMove)
        val delta1 = moveAction1 as SlideDirectClickAction.MouseMove
        assertEquals(15f, delta1.dx, 0.001f)
        assertEquals(5f, delta1.dy, 0.001f)
        assertEquals(2016L, delta1.eventTimeMs)

        val moveAction2 = detector.onTouchMove(pointerId = 1, x = 125f, y = 110f, eventTimeMs = 2032L)
        assertNotNull(moveAction2)
        assertTrue(moveAction2 is SlideDirectClickAction.MouseMove)
        val delta2 = moveAction2 as SlideDirectClickAction.MouseMove
        assertEquals(10f, delta2.dx, 0.001f)
        assertEquals(5f, delta2.dy, 0.001f)

        detector.onTouchUp(pointerId = 1, x = 125f, y = 110f, eventTimeMs = 2050L)
    }

    @Test
    fun quickDoubleTapProducesDirectClickAtSecondTapCoordinates() {
        val detector = SlideDirectClickGestureDetector(
            doubleTapTimeoutMs = 320L,
            doubleTapSlopPx = 96f,
        )

        // First tap: down and up
        val firstDown = detector.onTouchDown(pointerId = 0, x = 300f, y = 400f, eventTimeMs = 3000L)
        assertNull(firstDown)
        detector.onTouchUp(pointerId = 0, x = 300f, y = 400f, eventTimeMs = 3060L)

        // Second tap: 140ms later (well within 320ms), small distance offset (10px, 8px)
        val secondDown = detector.onTouchDown(pointerId = 0, x = 310f, y = 408f, eventTimeMs = 3200L)
        assertNotNull(secondDown)
        assertTrue(secondDown is SlideDirectClickAction.DirectClick)
        val directClick = secondDown as SlideDirectClickAction.DirectClick
        assertEquals(310f, directClick.x, 0.001f)
        assertEquals(408f, directClick.y, 0.001f)
        assertEquals(3200L, directClick.eventTimeMs)

        detector.onTouchUp(pointerId = 0, x = 310f, y = 408f, eventTimeMs = 3280L)
    }

    @Test
    fun slowDoubleTapDoesNotProduceDirectClick() {
        val detector = SlideDirectClickGestureDetector(
            doubleTapTimeoutMs = 320L,
            doubleTapSlopPx = 96f,
        )

        // First tap
        detector.onTouchDown(pointerId = 0, x = 200f, y = 200f, eventTimeMs = 5000L)
        detector.onTouchUp(pointerId = 0, x = 200f, y = 200f, eventTimeMs = 5050L)

        // Second tap: 400ms after first tap up (> 320ms threshold)
        val secondDown = detector.onTouchDown(pointerId = 0, x = 202f, y = 201f, eventTimeMs = 5450L)
        assertNull(secondDown)

        detector.onTouchUp(pointerId = 0, x = 202f, y = 201f, eventTimeMs = 5500L)
    }

    @Test
    fun doubleTapExceedingDistanceSlopDoesNotProduceDirectClick() {
        val detector = SlideDirectClickGestureDetector(
            doubleTapTimeoutMs = 320L,
            doubleTapSlopPx = 96f,
        )

        // First tap
        detector.onTouchDown(pointerId = 0, x = 100f, y = 100f, eventTimeMs = 6000L)
        detector.onTouchUp(pointerId = 0, x = 100f, y = 100f, eventTimeMs = 6050L)

        // Second tap within 320ms but far away (150px away > 96px slop)
        val secondDown = detector.onTouchDown(pointerId = 0, x = 250f, y = 100f, eventTimeMs = 6150L)
        assertNull(secondDown)
    }

    @Test
    fun directClickSuppressesMouseMoveUntilTouchUp() {
        val detector = SlideDirectClickGestureDetector(
            doubleTapTimeoutMs = 320L,
            doubleTapSlopPx = 96f,
        )

        // First tap
        detector.onTouchDown(pointerId = 0, x = 300f, y = 400f, eventTimeMs = 1000L)
        detector.onTouchUp(pointerId = 0, x = 300f, y = 400f, eventTimeMs = 1050L)

        // Second tap: direct click triggered
        val secondDown = detector.onTouchDown(pointerId = 0, x = 305f, y = 402f, eventTimeMs = 1150L)
        assertNotNull(secondDown)
        assertTrue(secondDown is SlideDirectClickAction.DirectClick)
        assertTrue(detector.isMoveSuppressed())

        // Micro-movements during the touch down must be suppressed (return null)
        val moveAction1 = detector.onTouchMove(pointerId = 0, x = 308f, y = 405f, eventTimeMs = 1170L)
        assertNull(moveAction1)
        assertTrue(detector.isMoveSuppressed())

        val moveAction2 = detector.onTouchMove(pointerId = 0, x = 312f, y = 410f, eventTimeMs = 1190L)
        assertNull(moveAction2)
        assertTrue(detector.isMoveSuppressed())

        // Lift finger: suppression ends
        detector.onTouchUp(pointerId = 0, x = 312f, y = 410f, eventTimeMs = 1250L)
        org.junit.Assert.assertFalse(detector.isMoveSuppressed())

        // Subsequent tap is a new slide gesture: movements should produce MouseMove
        detector.onTouchDown(pointerId = 0, x = 500f, y = 500f, eventTimeMs = 2000L)
        val moveAction3 = detector.onTouchMove(pointerId = 0, x = 520f, y = 510f, eventTimeMs = 2020L)
        assertNotNull(moveAction3)
        assertTrue(moveAction3 is SlideDirectClickAction.MouseMove)
    }

    @Test
    fun withDensityScalesDoubleTapSlopAndDragStartSlop() {
        val detector = SlideDirectClickGestureDetector.withDensity(density = 2.5f)
        // 48dp * 2.5 = 120px
        assertEquals(120f, detector.doubleTapSlopPx, 0.001f)
        // 4dp * 2.5 = 10px
        assertEquals(10f, detector.dragStartSlopPx, 0.001f)

        // First tap
        detector.onTouchDown(pointerId = 0, x = 100f, y = 100f, eventTimeMs = 1000L)
        detector.onTouchUp(pointerId = 0, x = 100f, y = 100f, eventTimeMs = 1050L)

        // Second tap at 110px offset: passes under 120px slop (would fail under 96px default)
        val secondDown = detector.onTouchDown(pointerId = 0, x = 210f, y = 100f, eventTimeMs = 1150L)
        assertNotNull(secondDown)
        assertTrue(secondDown is SlideDirectClickAction.DirectClick)
    }
}
