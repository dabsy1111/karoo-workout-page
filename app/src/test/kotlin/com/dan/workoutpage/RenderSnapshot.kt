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

/** Writes PNGs of the field when SNAPSHOT_DIR is set. Not a check, just pictures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h533dp-hdpi")
class RenderSnapshot {
    @Test fun snapshots() {
        val dir = System.getenv("SNAPSHOT_DIR") ?: return
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = PageState.PREVIEW
        val states = mapOf(
            "ready" to p.copy(status = WorkoutEngine.Status.READY, step = 1, stepElapsedSec = 0, samples = emptyMap(),
                stepAvg = null, stepMax = null, target = null, targetLow = null, targetHigh = null, power3s = 112,
                workoutName = "Sprints 3x10s", workoutCount = 2),
            "running" to p,
            "paused" to p.copy(status = WorkoutEngine.Status.PAUSED),
            "noplan" to PageState(power3s = 141, planNote = "Add intervals.icu API key in the Workout Page app", zoneTops = p.zoneTops),
        )
        for ((name, s) in states) for (h in listOf(744)) {
            val v = PageRenderer(ctx).render(s, 480, h).apply(ctx, FrameLayout(ctx))
            v.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 480, h)
            val bmp = Bitmap.createBitmap(480, h, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "${name}_$h.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
