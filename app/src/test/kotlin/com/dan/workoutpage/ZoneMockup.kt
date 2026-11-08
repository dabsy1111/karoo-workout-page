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

/** Pictures of the field at a Z3 and a Z4 effort, for checking colours and the arc needle. SNAPSHOT_DIR only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h533dp-hdpi")
class ZoneMockup {
    @Test fun render() {
        val dir = System.getenv("SNAPSHOT_DIR") ?: return
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val zones = PageState.zoneTops(250, emptyList())
        val ss = listOf(PlanStep(900, 135, 135, untilLap = true), PlanStep(1080, 220, 228), PlanStep(360, 145, 145, untilLap = true), PlanStep(1080, 220, 228))
        val thr = listOf(PlanStep(900, 145, 145, untilLap = true), PlanStep(1200, 236, 250), PlanStep(900, 145, 145, untilLap = true), PlanStep(1200, 236, 250))
        fun wave(n: Int, base: Int) = IntArray(n) { base + ((it * 7) % 19 - 9) }
        val states = mapOf(
            "z3" to PageState(power3s = 219, target = 224, targetLow = 220, targetHigh = 228, intervalRemainingSec = 452, step = 2, stepCount = 4,
                zoneTops = zones, ftp = 250, stepAvg = 223, stepMax = 241, stepElapsedSec = 628, plan = ss,
                samples = mapOf(0 to wave(900, 135), 1 to wave(628, 224)), status = WorkoutEngine.Status.RUNNING, workoutName = "Sweet Spot"),
            "z4" to PageState(power3s = 247, target = 243, targetLow = 236, targetHigh = 250, intervalRemainingSec = 480, step = 2, stepCount = 4,
                zoneTops = zones, ftp = 250, stepAvg = 242, stepMax = 278, stepElapsedSec = 720, plan = thr,
                samples = mapOf(0 to wave(900, 145), 1 to wave(720, 243)), status = WorkoutEngine.Status.RUNNING, workoutName = "Threshold"),
        )
        for ((name, s) in states) {
            val v = PageRenderer(ctx).render(s, 480, 744).apply(ctx, FrameLayout(ctx))
            v.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(744, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 480, 744)
            val bmp = Bitmap.createBitmap(480, 744, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
