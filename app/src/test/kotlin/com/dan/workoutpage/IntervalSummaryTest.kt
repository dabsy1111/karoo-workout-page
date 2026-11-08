package com.dan.workoutpage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntervalSummaryTest {
    private val plan = listOf(
        PlanStep(900, 145, 145),
        PlanStep(1200, 243, 243, isInterval = true),
        PlanStep(900, 145, 145, untilLap = true),
        PlanStep(1200, 243, 243, isInterval = true),
        PlanStep(900, 138, 138),
    )

    @Test fun oneRowPerRiddenInterval() {
        val samples = mapOf(0 to IntArray(900) { 140 }, 1 to IntArray(1200) { 240 }, 2 to IntArray(940) { 140 }, 3 to IntArray(1150) { 250 })
        val s = IntervalSummary.of(plan, samples)!!
        assertEquals(listOf(1, 2), s.rows.map { it.number })
        assertEquals(listOf(1200, 1150), s.rows.map { it.seconds })
        assertEquals(listOf(240, 250), s.rows.map { it.avg })
        assertEquals(listOf(240, 250), s.rows.map { it.np }) // steady power: NP = average
        assertEquals(2350, s.all.seconds)
        assertEquals(245, s.all.avg) // (1200*240 + 1150*250) / 2350 = 244.9
        assertEquals(250, s.all.max)
    }

    @Test fun skippedIntervalLeftOut() {
        val s = IntervalSummary.of(plan, mapOf(1 to IntArray(1200) { 240 }))!!
        assertEquals(1, s.rows.size)
    }

    @Test fun noMarkedIntervalsMeansNoSummary() {
        assertNull(IntervalSummary.of(plan.map { it.copy(isInterval = false) }, mapOf(1 to IntArray(60) { 200 })))
        assertNull(IntervalSummary.of(null, emptyMap()))
    }

    @Test fun npRewardsSurges() {
        val a = IntArray(600) { if ((it / 30) % 2 == 0) 300 else 150 } // 30 s on / 30 s off, avg 225
        assertEquals(225, IntervalSummary.of(listOf(PlanStep(600, 225, 225, isInterval = true)), mapOf(0 to a))!!.rows[0].avg)
        assert(IntervalSummary.np(a)!! > 235)
        assertNull(IntervalSummary.np(IntArray(20) { 500 })) // too short for NP
    }

    @Test fun missingReadingsCountAsZeroForNpButNotAverage() {
        val a = IntArray(60) { if (it < 30) 200 else -1 }
        val r = IntervalSummary.of(listOf(PlanStep(60, 200, 200, isInterval = true)), mapOf(0 to a))!!.rows[0]
        assertEquals(200, r.avg)
        assertEquals(60, r.seconds)
    }
}
