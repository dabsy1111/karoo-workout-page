package com.dan.workoutpage

import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lays out the field at Karoo 3 sizes (480 px wide, hdpi) and checks every piece of text
 * fits inside its own view, and every view stays inside the field.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h533dp-hdpi")
class RenderFitTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val busy = PageState.PREVIEW.copy(power3s = 1234, stepAvg = 1088, stepMax = 1512, intervalRemainingSec = 3725, step = 12, stepCount = 20,
        targetLow = 1000, targetHigh = 1250, target = 1125)

    private fun check(w: Int, h: Int, state: PageState) {
        val v = PageRenderer(ctx).render(state, w, h).apply(ctx, FrameLayout(ctx))
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val problems = mutableListOf<String>()
        fun walk(view: View, ox: Int, oy: Int) {
            val x = ox + view.left; val y = oy + view.top
            if (view.visibility != View.VISIBLE) return // hidden views (the lap reminder) don't need to fit
            if (view is TextView && view.text.isNotEmpty()) {
                val name = ctx.resources.getResourceEntryName(view.id)
                val fm = view.paint.fontMetricsInt
                val needH = fm.descent - fm.ascent
                val needW = view.paint.measureText(view.text.toString())
                if (needH > view.height - view.paddingTop - view.paddingBottom + 1) problems += "$name '${view.text}' too tall ($needH > ${view.height})"
                // Views set to ellipsize (the workout name) may shorten on purpose; everything else must fit whole.
                if (view.ellipsize == null && needW > view.width - view.paddingLeft - view.paddingRight + 1) problems += "$name '${view.text}' too wide (${needW.toInt()} > ${view.width})"
                if (y < 0 || y + view.height > h + 1 || x < 0 || x + view.width > w + 1) problems += "$name outside field"
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), x, y)
        }
        walk(v, -v.left, -v.top)
        println("${w}x$h: " + if (problems.isEmpty()) "fits" else problems.joinToString("; "))
        assertTrue("${w}x$h: $problems", problems.isEmpty())
    }

    // The field fills a page: ~630 px in the page editor, ~740 while riding. Smaller sizes in case Karoo adds other fields.
    private val heights = listOf(480, 560, 630, 700, 744)
    @Test fun busyNumbersFit() { heights.forEach { check(480, it, busy) } }
    @Test fun normalWorkoutFits() { heights.forEach { check(480, it, PageState.PREVIEW) } }
    @Test fun pausedFits() { heights.forEach { check(480, it, PageState.PREVIEW.copy(status = WorkoutEngine.Status.PAUSED, step = 12, stepCount = 20)) } }
    @Test fun readyFits() {
        heights.forEach { check(480, it, PageState.PREVIEW.copy(status = WorkoutEngine.Status.READY, step = 1, workoutName = "Threshold 2x20min with a long name", workoutCount = 2)) }
    }
    @Test fun lapReminderFits() {
        val lapPlan = PageState.PREVIEW.plan!!.toMutableList().also { it[2] = PlanStep(900, 140, 150, untilLap = true) }
        heights.forEach { check(480, it, PageState.PREVIEW.copy(plan = lapPlan, intervalRemainingSec = -20, stepElapsedSec = 920)) }
    }
    @Test fun finishedFits() { heights.forEach { check(480, it, PageState.PREVIEW.copy(status = WorkoutEngine.Status.FINISHED)) } }
    private fun finished(n: Int, sec: Int, w: Int): PageState {
        val plan = (0 until n).flatMap { listOf(PlanStep(sec, w - 10, w + 10, isInterval = true), PlanStep(120, 140, 140)) }
        val samples = plan.indices.associateWith { i -> IntArray(plan[i].durationSec) { plan[i].mid!! + (it % 7) } }
        return PageState(status = WorkoutEngine.Status.FINISHED, plan = plan, samples = samples, step = plan.size, stepCount = plan.size,
            zoneTops = PageState.zoneTops(250, emptyList()), workoutName = "Sweet Spot 3x18min with a long name")
    }
    @Test fun summaryFitsFewIntervals() { heights.forEach { check(480, it, finished(2, 1200, 243)) } }
    @Test fun summaryFitsManyIntervals() { heights.forEach { check(480, it, finished(12, 3725, 1050)) } }

    @Test fun noWorkoutFits() { heights.forEach { check(480, it, PageState(planNote = "Add intervals.icu API key in the Workout Page app")) } }
}
