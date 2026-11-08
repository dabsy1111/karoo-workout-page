package com.dan.workoutpage

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageCacheTest {
    private val r = PageRenderer(ApplicationProvider.getApplicationContext())
    private val s = PageState.PREVIEW

    @Test fun imagesAreHalfSize() {
        val a = r.arcImage(s, 480, 76)
        assertEquals(240, a.width); assertEquals(38, a.height)
        val g = r.graphImage(s, 468, 116)
        assertEquals(234, g.width); assertEquals(58, g.height)
    }

    @Test fun arcRedrawnOnlyWhenPowerOrTargetChanges() {
        val a1 = r.arcImage(s, 480, 76)
        assertSame(a1, r.arcImage(s.copy(stepAvg = 999, intervalRemainingSec = 3), 480, 76)) // unrelated change
        assertNotSame(a1, r.arcImage(s.copy(power3s = s.power3s!! + 1), 480, 76))
    }

    @Test fun graphReusedWithinFiveSecondsUnlessTheStepChanges() {
        val g1 = r.graphImage(s, 468, 116)
        assertSame(g1, r.graphImage(s.copy(stepElapsedSec = s.stepElapsedSec + 1, power3s = 300), 468, 116))
        assertNotSame(g1, r.graphImage(s.copy(step = s.step!! + 1), 468, 116))
    }
}
