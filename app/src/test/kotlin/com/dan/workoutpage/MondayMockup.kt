package com.dan.workoutpage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * Rides Dan's Monday "Threshold 2x20min" through the real parser, engine and tracker with
 * simulated power, and saves what the field shows at four moments. Runs only when SNAPSHOT_DIR is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h533dp-hdpi")
class MondayMockup {
    // As intervals.icu returns it with resolve=true (FTP 250): the workout text from the calendar
    // "Press lap 15m 58% / 20m 97% / Press lap 15m 58% / 20m 97% / 15m 55%".
    private val json = """
    [{"category": "WORKOUT", "type": "Ride", "name": "Threshold 2x20min",
      "workout_doc": {"ftp": 250, "steps": [
        {"duration": 900, "until_lap_press": true, "warmup": true, "power": {"value": 58, "units": "%ftp"}, "_power": {"value": 145}},
        {"reps": 1, "steps": [
          {"duration": 1200, "intensity": "interval", "power": {"value": 97, "units": "%ftp"}, "_power": {"value": 242.5}},
          {"duration": 900, "until_lap_press": true, "power": {"value": 58, "units": "%ftp"}, "_power": {"value": 145}},
          {"duration": 1200, "intensity": "interval", "power": {"value": 97, "units": "%ftp"}, "_power": {"value": 242.5}}
        ]},
        {"duration": 900, "cooldown": true, "power": {"value": 55, "units": "%ftp"}, "_power": {"value": 137.5}}
      ]}}]
    """

    private val zones = PageState.zoneTops(250, emptyList())
    private var t = 0

    /** Believable power: around the target with slow drift, pedal noise and the odd surge. */
    private fun power(target: Int): Int {
        t++
        val drift = 6 * sin(t / 47.0) + 4 * sin(t / 13.0)
        val noise = ((t * 7919) % 23 - 11) * 0.8
        val surge = if (t % 173 in 0..4) 22.0 else 0.0
        val fade = if (target > 200) -(t % 1200) / 1200.0 * 4 else 0.0 // a little fade late in a rep
        return (target + drift + noise + surge + fade).toInt()
    }

    private fun state(e: WorkoutEngine, tr: WorkoutTracker, last3: List<Int>, count: Int): PageState {
        val active = e.status == WorkoutEngine.Status.RUNNING || e.status == WorkoutEngine.Status.PAUSED
        val range = PageState(zoneTops = zones).easyZoneRange(e.target) ?: e.range // as LiveWorkout does
        return PageState(
            power3s = if (last3.isEmpty()) 128 else last3.takeLast(3).average().toInt(),
            target = e.target.takeIf { active }, targetLow = range?.first.takeIf { active }, targetHigh = range?.last.takeIf { active },
            intervalRemainingSec = e.remaining?.toLong(), step = e.index + 1, stepCount = e.steps.size,
            zoneTops = zones, ftp = 250, stepAvg = tr.avg, stepMax = tr.maxPower, stepElapsedSec = e.elapsed,
            plan = e.steps, samples = tr.samples(), status = e.status, workoutName = e.name, workoutCount = count,
        )
    }

    // Thursday's sweet spot, with no intensity on the steps: the "intensity=interval" lines in the text mark the reps.
    private val sweetSpot = """
    [{"category": "WORKOUT", "type": "Ride", "name": "Sweet Spot 3x18min",
      "description": "Hard day 2 of 2.\n\nWarmup\n- Press lap 15m 54% intensity=warmup\n\nMain Set 3x\n- 18m 90% intensity=interval\n- Press lap 6m 58% intensity=recovery\n\nCooldown\n- 10m 54% intensity=cooldown",
      "workout_doc": {"ftp": 250, "steps": [
        {"duration": 900, "until_lap_press": true, "_power": {"value": 135}},
        {"reps": 3, "steps": [
          {"duration": 1080, "_power": {"value": 225, "start": 220, "end": 228}},
          {"duration": 360, "until_lap_press": true, "_power": {"value": 145}}
        ]},
        {"duration": 600, "_power": {"value": 135}}
      ]}}]
    """

    @Test fun renderSweetSpotSummary() {
        val dir = System.getenv("SNAPSHOT_DIR") ?: return
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val w = WorkoutPlan.parseEvents(sweetSpot).single()
        val e = WorkoutEngine(w.name, w.expanded)
        val tr = WorkoutTracker()
        e.start()
        var guard = 0
        while (e.status == WorkoutEngine.Status.RUNNING && guard++ < 20000) {
            val p = power(e.target ?: 140)
            tr.tick(e.index, true, p)
            e.tick(true)
            if (e.step?.untilLap == true && (e.remaining ?: 1) <= -15) e.lapPressed()
        }
        val s = state(e, tr, listOf(140), 1)
        val v = PageRenderer(ctx).render(s, 480, 744).apply(ctx, FrameLayout(ctx))
        v.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(744, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 480, 744)
        val bmp = Bitmap.createBitmap(480, 744, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(dir, "6_sweetspot_done.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun render() {
        val dir = System.getenv("SNAPSHOT_DIR") ?: return
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val w = WorkoutPlan.parseEvents(json).single()
        val e = WorkoutEngine(w.name, w.expanded)
        val tr = WorkoutTracker()
        val recent = ArrayList<Int>()
        val shots = LinkedHashMap<String, PageState>()

        fun ride(seconds: Int) = repeat(seconds) {
            val p = power(e.target ?: 140)
            recent += p
            tr.tick(e.index, true, p) // same order as LiveWorkout: record, then advance
            e.tick(true)
        }

        shots["1_ready"] = state(e, tr, recent, 1)
        e.start()
        ride(15 * 60 + 40); e.lapPressed() // warm-up: lap pressed at 15:40
        ride(12 * 60) // 12 minutes into rep 1
        shots["2_rep1"] = state(e, tr, recent, 1)
        ride(8 * 60) // rep 1 ends on time, recovery starts
        ride(15 * 60 + 20) // past the planned 15:00: waiting for the lap button
        shots["3_recovery"] = state(e, tr, recent, 1)
        e.lapPressed()
        ride(18 * 60 + 2) // 1:58 left in rep 2
        shots["4_rep2"] = state(e, tr, recent, 1)
        ride(2 * 60 - 2 + 15 * 60) // rest of rep 2 and the cool-down
        shots["5_done"] = state(e, tr, recent, 1)

        for ((name, s) in shots) {
            val v = PageRenderer(ctx).render(s, 480, 744).apply(ctx, FrameLayout(ctx))
            v.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(744, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 480, 744)
            val bmp = Bitmap.createBitmap(480, 744, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
