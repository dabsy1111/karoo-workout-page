package com.dan.workoutpage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutTrackerTest {
    @Test fun stepAverageAndMaxResetEachStep() {
        val t = WorkoutTracker()
        listOf(100, 200, 300).forEach { t.tick(0, true, it) }
        assertEquals(200, t.avg); assertEquals(300, t.maxPower); assertEquals(3, t.elapsedSec)
        t.tick(1, true, 50)
        assertEquals(50, t.avg); assertEquals(50, t.maxPower); assertEquals(1, t.elapsedSec)
        assertArrayEquals(intArrayOf(100, 200, 300), t.samples()[0])
    }

    @Test fun pausedTimeIsNotCounted() {
        val t = WorkoutTracker()
        t.tick(0, true, 200)
        t.tick(0, false, 999)
        assertEquals(1, t.elapsedSec); assertEquals(200, t.maxPower)
    }

    @Test fun missingPowerLeavesAGapButKeepsTime() {
        val t = WorkoutTracker()
        t.tick(0, true, null); t.tick(0, true, 150)
        assertEquals(2, t.elapsedSec); assertEquals(150, t.avg)
        assertArrayEquals(intArrayOf(-1, 150), t.samples()[0])
    }

    @Test fun rewindStartsThatStepAgain() {
        val t = WorkoutTracker()
        t.tick(0, true, 100); t.tick(1, true, 300); t.tick(0, true, 120)
        assertArrayEquals(intArrayOf(120), t.samples()[0])
        assertArrayEquals(intArrayOf(300), t.samples()[1])
    }

    @Test fun resetClearsEverything() {
        val t = WorkoutTracker()
        t.tick(0, true, 100); t.reset()
        assertNull(t.avg); assertEquals(0, t.samples().size)
    }
}
