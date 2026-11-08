package com.dan.workoutpage

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GraphLayoutTest {
    private val plan = listOf(PlanStep(600, 100, 150), PlanStep(10, 450, 500), PlanStep(120, 1, 120))

    @Test fun doneStepsUseActualTimeFutureUsesPlan() {
        val s = PageState(step = 2, stepCount = 3, plan = plan, stepElapsedSec = 4,
            samples = mapOf(0 to IntArray(540), 1 to IntArray(4)))
        assertArrayEquals(intArrayOf(540, 10, 120), GraphLayout.widths(s))
    }

    @Test fun overrunningStepGrows() {
        val s = PageState(step = 1, stepCount = 3, plan = plan, stepElapsedSec = 700)
        assertArrayEquals(intArrayOf(700, 10, 120), GraphLayout.widths(s))
    }

    @Test fun withoutPlanOnlyKnownTime() {
        val s = PageState(step = 2, stepCount = 5, intervalRemainingSec = 30, stepElapsedSec = 15,
            samples = mapOf(0 to IntArray(300), 1 to IntArray(15)))
        assertArrayEquals(intArrayOf(300, 45), GraphLayout.widths(s))
    }

    @Test fun finishedShowsWhatWasRidden() {
        val s = PageState(step = 3, stepCount = 3, plan = plan, status = WorkoutEngine.Status.FINISHED,
            samples = mapOf(0 to IntArray(600), 1 to IntArray(12), 2 to IntArray(100)))
        assertArrayEquals(intArrayOf(600, 12, 100), GraphLayout.widths(s))
    }

    @Test fun noWorkoutNoGraph() {
        assertArrayEquals(IntArray(0), GraphLayout.widths(PageState()))
    }
}
