package com.dan.workoutpage

import com.dan.workoutpage.WorkoutEngine.Event
import com.dan.workoutpage.WorkoutEngine.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutEngineTest {
    private fun engine() = WorkoutEngine("Test", listOf(
        PlanStep(5, 100, 140, ramp = true),
        PlanStep(4, 300, 320),
        PlanStep(600, 140, 140, untilLap = true),
        PlanStep(3, 120, 130),
    ))

    @Test fun waitsForStart() {
        val e = engine()
        assertEquals(Status.READY, e.status)
        assertTrue(e.tick(true).isEmpty())
        assertEquals(0, e.elapsed)
        assertEquals(listOf(Event.StepStarted(0, true)), e.start())
        assertEquals(Status.RUNNING, e.status)
    }

    @Test fun timedStepsAdvanceWithCountdownBeeps() {
        val e = engine(); e.start()
        val events = (1..5).flatMap { e.tick(true) }
        assertEquals(listOf(Event.Countdown(3), Event.Countdown(2), Event.Countdown(1), Event.StepStarted(1, false)), events)
        assertEquals(1, e.index); assertEquals(0, e.elapsed); assertEquals(4, e.remaining)
    }

    @Test fun onlyRecordingTimeCounts() {
        val e = engine(); e.start()
        e.tick(false); e.tick(false)
        assertEquals(0, e.elapsed)
        e.togglePause(); e.tick(true)
        assertEquals(Status.PAUSED, e.status); assertEquals(0, e.elapsed)
        e.togglePause(); e.tick(true)
        assertEquals(1, e.elapsed)
    }

    @Test fun rampTargetMoves() {
        val e = engine(); e.start()
        assertEquals(100, e.target)
        repeat(2) { e.tick(true) }
        assertEquals(116, e.target) // 100 + 40 * 2/5
        assertEquals(113..119, e.range)
    }

    @Test fun lapStepWaitsForLapButtonOrNext() {
        val e = engine(); e.start()
        e.next(); e.next()
        assertEquals(2, e.index)
        val events = (1..700).flatMap { e.tick(true) } // past its planned 10 min: still waiting
        assertEquals(listOf(Event.LapDue), events) // reminder once, at 10:00
        assertEquals(2, e.index); assertEquals(-100, e.remaining)
        assertEquals(listOf(Event.StepStarted(3, true)), e.lapPressed())
        assertTrue(e.lapPressed().isEmpty()) // lap button does nothing on a timed step
    }

    @Test fun backRestartsThenGoesBack() {
        val e = engine(); e.start(); e.next()
        repeat(2) { e.tick(true) }
        e.back()
        assertEquals(0, e.index) // only 2 s in: previous step
        e.next(); repeat(3) { e.tick(true) } // now 3 s into step 1 (of 4)
        e.next(); repeat(11) { e.tick(true) } // 11 s into the lap step
        e.back()
        assertEquals(2, e.index); assertEquals(0, e.elapsed) // more than 10 s in: restart this step
    }

    @Test fun finishesAndCanGoBack() {
        val e = engine(); e.start(); e.next(); e.next(); e.next()
        assertEquals(listOf(Event.Finished), e.next())
        assertEquals(Status.FINISHED, e.status)
        assertTrue(e.tick(true).isEmpty())
        e.back()
        assertEquals(Status.RUNNING, e.status); assertEquals(3, e.index)
    }

    @Test fun singleTargetGetsABand() {
        val e = WorkoutEngine("T", listOf(PlanStep(1200, 243, 243)))
        e.start()
        assertEquals(243, e.target); assertEquals(236..250, e.range)
    }

    @Test fun freeRideHasNoTarget() {
        val e = WorkoutEngine("F", listOf(PlanStep(60, null, null)))
        e.start()
        assertNull(e.target); assertNull(e.range)
    }

    @Test fun restoreCarriesOn() {
        val e = engine()
        e.restore(Status.RUNNING, 2, 95)
        assertEquals(2, e.index); assertEquals(95, e.elapsed)
    }
}
