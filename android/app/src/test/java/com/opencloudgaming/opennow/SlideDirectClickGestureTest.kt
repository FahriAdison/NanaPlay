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
}
