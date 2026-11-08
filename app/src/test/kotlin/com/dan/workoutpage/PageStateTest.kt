package com.dan.workoutpage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageStateTest {

    @Test fun singleTargetGetsFivePercentBand() {
        assertEquals(190..210, PageState(target = 200).range)
        assertEquals(238..262, PageState(target = 250, targetLow = 238, targetHigh = 262).range)
    }

    @Test fun zonesFromFtpUseDefaultPercentages() {
        val tops = PageState.zoneTops(300, emptyList())
        assertEquals(listOf(165, 225, 270, 315, 360, 450), tops)
        val s = PageState(zoneTops = tops)
        assertEquals(0, s.zoneIndex(100))   // Z1
        assertEquals(0, s.zoneIndex(165))   // top of Z1 is still Z1
        assertEquals(1, s.zoneIndex(166))   // Z2
        assertEquals(3, s.zoneIndex(300))   // Z4 at FTP
        assertEquals(6, s.zoneIndex(800))   // Z7
        assertNull(s.zoneIndex(null))
    }

    @Test fun karooZonesUsedWhenThereAreSeven() {
        val karoo = listOf(0 to 150, 151 to 210, 211 to 250, 251 to 290, 291 to 330, 331 to 420, 421 to 2000)
        assertEquals(listOf(150, 210, 250, 290, 330, 420), PageState.zoneTops(280, karoo))
        // Some other zone count: fall back to FTP percentages.
        assertEquals(6, PageState.zoneTops(280, listOf(0 to 100, 101 to 2000)).size)
    }

    @Test fun noZonesMeansNoColour() {
        assertNull(PageState().zoneIndex(250))
        assertTrue(PageState.zoneTops(0, emptyList()).isEmpty())
        assertFalse(PageState(power3s = 300).workoutActive)
        assertFalse(PageState(status = WorkoutEngine.Status.READY).workoutActive)
        assertTrue(PageState(status = WorkoutEngine.Status.PAUSED).workoutActive)
    }

    @Test fun easyStepsUseTheWholeZone() {
        val s = PageState(zoneTops = PageState.zoneTops(250, emptyList())) // 138, 188, 225, 263, 300, 375
        assertEquals(0..138, s.easyZoneRange(120))          // Z1
        assertEquals(139..188, s.easyZoneRange(145))        // Z2, e.g. a 58% recovery
        assertNull(s.easyZoneRange(243))                    // Z4 keeps the workout's range
        assertEquals("139–188 W", s.rangeText(PlanStep(900, 141, 149)))
        assertEquals("236–250 W", s.rangeText(PlanStep(1200, 236, 250)))
        assertEquals("Free ride", s.rangeText(PlanStep(300, null, null)))
        assertEquals("145 W", PageState().rangeText(PlanStep(900, 145, 145))) // zones unknown: workout's own
    }

    @Test fun durationsLookLikeKaroo() {
        assertEquals("00:09", Format.duration(9))
        assertEquals("12:30", Format.duration(750))
        assertEquals("1:05:00", Format.duration(3900))
        assertEquals("open", Format.duration(-1))
        assertEquals("--", Format.duration(null))
    }

    @Test fun stepAndRange() {
        assertEquals("6/14", Format.step(6, 14))
        assertEquals("--", Format.step(null, null))
        assertEquals("238–262", Format.range(238..262))
        assertEquals("0:09", Format.countdown(9))
        assertEquals("14:40", Format.countdown(880))
        assertEquals("1:02:00", Format.countdown(3720))
    }
}
