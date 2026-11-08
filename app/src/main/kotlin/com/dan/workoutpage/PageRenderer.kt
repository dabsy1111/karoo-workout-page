package com.dan.workoutpage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.RemoteViews
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the Garmin-style workout field: target arc, AVG | 3s power | MAX, target range,
 * STEPS n/N, the workout graph, and the current + next two steps.
 */
class PageRenderer(private val context: Context) {

    /** Row weights from workout_page.xml. */
    private object Rows {
        const val TOP = 40f; const val STEPS = 7f; const val GRAPH = 16f; const val LIST = 27f; const val CONTROLS = 10f
        const val TOTAL = TOP + STEPS + GRAPH + LIST + CONTROLS
        const val ARC = 0.26f; const val NUMBERS = 0.56f; const val RANGE = 0.18f // shares of TOP
        const val POWER = 0.68f // the power number's own band, overlapping the inside of the arc
        const val CUR = 0.70f; const val NEXT = 0.30f // shares of LIST
    }

    fun render(state: PageState, widthPx: Int, heightPx: Int): RemoteViews {
        val w = max(widthPx, 160).toFloat()
        val h = max(heightPx, 160).toFloat()
        val dp = context.resources.displayMetrics.density
        if (state.status == WorkoutEngine.Status.FINISHED) {
            IntervalSummary.of(state.plan, state.samples)?.let { return renderSummary(state, it, w, h) }
        }
        val rv = RemoteViews(context.packageName, R.layout.workout_page)
        fun px(id: Int, size: Float) = rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, max(size, 9f))

        // Real pixel height of each row: field minus padding, the 1dp divider and the controls' margin, split by weight.
        val inner = h - 8 * dp - 1 * dp - 4 * dp
        fun row(weight: Float) = inner * weight / Rows.TOTAL
        val top = row(Rows.TOP)
        val numbersH = top * Rows.NUMBERS
        val list = row(Rows.LIST) - 6 * dp // row top margins
        val lineH = 1.18f

        // Text sizes, each capped by its row height and its share of the width.
        val label = (0.032f * w).coerceIn(10f, 18f)
        val powerText = Format.watts(state.power3s)
        val powerEms = if (powerText.length >= 4) 2.15f else 1.62f
        val power = min(top * Rows.POWER / lineH * 0.98f, 0.56f * w / powerEms)
        val avgText = Format.watts(state.stepAvg.takeIf { state.workoutActive })
        val maxText = Format.watts(state.stepMax.takeIf { state.workoutActive })
        val sideEms = if (max(avgText.length, maxText.length) >= 4) 2.4f else 1.85f
        val side = min((numbersH * 0.8f) / lineH - label, 0.22f * w / sideEms)
        val range = min(top * Rows.RANGE / lineH, 0.6f * w / 6f)
        val steps = min(row(Rows.STEPS) / lineH * 0.8f, 0.045f * w)
        val curTimeH = list * Rows.CUR - 4 * dp
        val nextText = min((list * Rows.NEXT - 4 * dp) / lineH, 0.30f * w / 2.9f)

        // The sizes above guess how wide digits are. The Karoo draws in its own font, which is wider
        // than a condensed one, so measure each text in the font actually on this device and shrink to fit.
        val innerW = w - 8 * dp
        val stepRowW = innerW - 6 * dp
        px(R.id.label_avg, label); px(R.id.label_max, label)
        val sideFit = min(fit(avgText, side, innerW * 0.22f - 4 * dp), fit(maxText, side, innerW * 0.22f - 4 * dp))
        px(R.id.avg, sideFit); px(R.id.max, sideFit)
        px(R.id.power, fit(powerText, power, innerW * 0.56f - 4 * dp))
        val rangeText = state.range?.let { "${it.first}–${it.last} W" } ?: ""
        px(R.id.range, fit(rangeText, range, innerW * 0.95f, bold = false))
        px(R.id.label_steps, steps)
        val nextStep = when (state.status) {
            WorkoutEngine.Status.READY -> state.plan?.firstOrNull()
            WorkoutEngine.Status.FINISHED -> null
            else -> state.upcoming(1).firstOrNull()
        }
        val nextDurText = nextStep?.let { if (it.durationSec > 0) Format.stepLength(it.durationSec) else "Lap" } ?: ""
        val nextRangeText = nextStep?.let { state.rangeText(it) } ?: "Last step"
        px(R.id.n1_dur, fit(nextDurText, nextText, stepRowW * 0.34f - 8 * dp))
        px(R.id.n1_range, fit(nextRangeText, nextText, stepRowW * 0.66f - 4 * dp))

        // Top section
        rv.setTextViewText(R.id.power, powerText)
        rv.setTextColor(R.id.power, colourFor(state.zoneIndex(state.power3s)))
        rv.setTextViewText(R.id.avg, avgText)
        rv.setTextViewText(R.id.max, maxText)
        rv.setTextViewText(R.id.range, rangeText)
        rv.setImageViewBitmap(R.id.arc, arcImage(state, w.toInt(), max((top * Rows.ARC).toInt(), 12)))

        // STEPS row. Before the start it names the workout; tap the name to switch if there are several today.
        val status = state.status
        rv.setTextViewText(R.id.label_steps, when (status) {
            // "1/2 · name" when there are several: the counter goes first so a long name can't push it off.
            WorkoutEngine.Status.READY -> (if (state.workoutCount > 1) "${state.workoutChoice + 1}/${state.workoutCount} · " else "") + (state.workoutName ?: "Workout")
            else -> "STEPS"
        })
        val stepsText = when (status) {
            null -> "No workout"
            WorkoutEngine.Status.READY -> "${state.stepCount} steps"
            WorkoutEngine.Status.PAUSED -> "PAUSED  " + Format.step(state.step, state.stepCount)
            WorkoutEngine.Status.RUNNING -> Format.step(state.step, state.stepCount)
            WorkoutEngine.Status.FINISHED -> "Done"
        }
        rv.setTextViewText(R.id.steps, stepsText)
        px(R.id.steps, fit(stepsText, steps, innerW * 0.45f))
        rv.setTextColor(R.id.steps, if (status == WorkoutEngine.Status.PAUSED) Color.parseColor("#C62828") else INK)

        // Graph
        rv.setImageViewBitmap(R.id.graph, graphImage(state, w.toInt() - (8 * dp).toInt(), max(row(Rows.GRAPH).toInt(), 12)))

        // Current step: big time left across the full width. The graph's "now" line shows progress.
        val remaining = state.intervalRemainingSec
        val lapStep = state.stepIndex?.let { state.plan?.getOrNull(it)?.untilLap } == true
        val lapDue = state.workoutActive && lapStep && remaining != null && remaining <= 0
        val curText = when {
            status == WorkoutEngine.Status.READY -> Format.countdown(state.plan.orEmpty().sumOf { it.durationSec }.toLong())
            status == WorkoutEngine.Status.FINISHED -> "Done"
            !state.workoutActive -> Format.DASH
            lapDue -> LiveWorkout.LAP_REMINDER
            remaining == null -> Format.DASH
            else -> Format.countdown(remaining.coerceAtLeast(0))
        }
        val textW = w - 8 * dp - 6 * dp
        // Condensed bold digits are ~0.56 em wide; the reminder is a sentence, so it gets sized to the width.
        val curEms = if (lapDue) 15.5f else max(2.0f, curText.length * 0.56f)
        px(R.id.cur_time, fit(curText, min(curTimeH / lineH, 0.94f * textW / curEms), textW * 0.96f))
        rv.setTextViewText(R.id.cur_time, curText)
        rv.setTextColor(R.id.cur_time, if (lapDue) Color.parseColor("#C62828") else INK)

        rv.setInt(R.id.bar_cur, "setBackgroundColor", barColour(state, state.target))

        // The step after this one (before the start: the first step).
        val next = nextStep
        if (next != null) {
            // Lap steps show their planned time like any other step; the reminder comes when it's up.
            rv.setTextViewText(R.id.n1_dur, nextDurText)
            rv.setTextViewText(R.id.n1_range, nextRangeText)
            rv.setInt(R.id.bar_n1, "setBackgroundColor", barColour(state, next.mid))
            rv.setInt(R.id.n1_dur, "setBackgroundResource", R.drawable.time_box)
        } else {
            rv.setTextViewText(R.id.n1_dur, "")
            rv.setTextViewText(R.id.n1_range, if (state.plan != null && state.workoutActive) "Last step" else "")
            rv.setInt(R.id.bar_n1, "setBackgroundColor", Color.TRANSPARENT)
            rv.setInt(R.id.n1_dur, "setBackgroundColor", Color.TRANSPARENT)
        }

        setControls(rv, state)
        rv.setOnClickPendingIntent(R.id.label_steps, ControlReceiver.pendingIntent(context, LiveWorkout.Control.CHOOSE))
        return rv
    }

    /** Back / Start-Pause / Next: same ids in the workout screen and the summary. */
    private fun setControls(rv: RemoteViews, state: PageState) {
        val status = state.status
        rv.setImageViewResource(R.id.btn_play, if (status == WorkoutEngine.Status.RUNNING) R.drawable.ic_ctl_pause else R.drawable.ic_ctl_play)
        rv.setInt(R.id.btn_play, "setBackgroundResource", if (status == WorkoutEngine.Status.READY) R.drawable.ctl_button_go else R.drawable.ctl_button)
        val canPlay = status == WorkoutEngine.Status.READY || state.workoutActive
        val canSkip = state.workoutActive
        val canBack = state.workoutActive || status == WorkoutEngine.Status.FINISHED
        rv.setInt(R.id.btn_play, "setImageAlpha", if (canPlay) 255 else 70)
        rv.setInt(R.id.btn_next, "setImageAlpha", if (canSkip) 255 else 70)
        rv.setInt(R.id.btn_back, "setImageAlpha", if (canBack) 255 else 70)
        rv.setOnClickPendingIntent(R.id.btn_play, ControlReceiver.pendingIntent(context, LiveWorkout.Control.START_PAUSE))
        rv.setOnClickPendingIntent(R.id.btn_next, ControlReceiver.pendingIntent(context, LiveWorkout.Control.NEXT))
        rv.setOnClickPendingIntent(R.id.btn_back, ControlReceiver.pendingIntent(context, LiveWorkout.Control.BACK))
    }

    // ---- Post-workout summary ----------------------------------------------------------------

    private object SumRows {
        const val TITLE = 9f; const val HEAD = 6f; const val ROWS = 63f; const val TOTAL = 11f; const val CONTROLS = 11f
        const val TOTAL_W = TITLE + HEAD + ROWS + TOTAL + CONTROLS
        /** Fewer intervals than this still get rows this tall, so 2 reps don't turn into giant text. */
        const val MIN_SLOTS = 5
        /** Column widths, as in summary_row.xml. */
        val COLS = floatArrayOf(9f, 21f, 22f, 16f, 16f, 16f)
    }

    private fun renderSummary(state: PageState, sum: IntervalSummary, w: Float, h: Float): RemoteViews {
        val dp = context.resources.displayMetrics.density
        val rv = RemoteViews(context.packageName, R.layout.workout_summary)
        val inner = h - 8 * dp - 1 * dp - 4 * dp
        val innerW = w - 8 * dp
        fun row(weight: Float) = inner * weight / SumRows.TOTAL_W
        fun px(target: RemoteViews, id: Int, size: Float) = target.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, max(size, 9f))
        fun colW(i: Int) = innerW * SumRows.COLS[i] / 100f - 4 * dp // a little air between columns

        val title = state.workoutName ?: "Workout"
        val titleSize = row(SumRows.TITLE) / 1.25f
        px(rv, R.id.sum_title, fit(title, titleSize, innerW * 0.75f))
        rv.setTextViewText(R.id.sum_title, title)
        px(rv, R.id.sum_done, titleSize * 0.8f)
        val head = min(row(SumRows.HEAD) / 1.25f, 18f)
        listOf(R.id.h_num, R.id.h_time, R.id.h_target, R.id.h_avg, R.id.h_np, R.id.h_max).forEach { px(rv, it, head) }

        val slots = max(sum.rows.size, SumRows.MIN_SLOTS)
        val cellH = row(SumRows.ROWS) / slots / 1.25f
        fun cells(r: IntervalRow?, label: String?): List<String> =
            if (r == null) List(6) { "" }
            else listOf(label ?: "${r.number}", Format.stepLength(r.seconds), if (label != null) "" else targetText(r.target),
                Format.watts(r.avg), Format.watts(r.np), Format.watts(r.max))
        val ids = intArrayOf(R.id.c_num, R.id.c_time, R.id.c_target, R.id.c_avg, R.id.c_np, R.id.c_max)

        // One size for every row, small enough for the widest value in each column.
        val allRows = sum.rows.map { cells(it, null) } + listOf(cells(sum.all, "All"))
        var size = cellH
        for (c in 0 until 6) for (r in allRows) size = min(size, fit(r[c], size, colW(c), bold = c != 2))
        var totalSize = min(row(SumRows.TOTAL) / 1.25f, size * 1.1f)
        val allCells = cells(sum.all, "All")
        for (c in 0 until 6) totalSize = min(totalSize, fit(allCells[c], totalSize, colW(c) - 4 * dp))

        fun addRow(container: Int, r: IntervalRow?, label: String?, textSize: Float) {
            val line = RemoteViews(context.packageName, R.layout.summary_row)
            val texts = cells(r, label)
            for (c in 0 until 6) {
                line.setTextViewText(ids[c], texts[c])
                px(line, ids[c], textSize)
            }
            if (r != null) line.setTextColor(R.id.c_avg, colourFor(state.zoneIndex(r.avg)))
            rv.addView(container, line)
        }
        sum.rows.forEach { addRow(R.id.sum_rows, it, null, size) }
        repeat(slots - sum.rows.size) { addRow(R.id.sum_rows, null, null, size) } // keep rows at a sensible height
        addRow(R.id.sum_total, sum.all, "All", totalSize)

        setControls(rv, state)
        return rv
    }

    /** Target without the "W", to fit the table: "243", "236–250", "125→163". */
    private fun targetText(st: PlanStep): String = when {
        st.lo == null || st.hi == null -> "--"
        st.ramp -> "${st.lo}→${st.hi}"
        st.lo == st.hi -> "${st.lo}"
        else -> "${st.lo}–${st.hi}"
    }

    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldFace = Typeface.create(KAROO_FONT, Typeface.BOLD)
    private val plainFace = Typeface.create(KAROO_FONT, Typeface.NORMAL)

    /** [size], or smaller if [text] at that size would be wider than [maxWidth] in this device's font. */
    private fun fit(text: String, size: Float, maxWidth: Float, bold: Boolean = true): Float {
        if (text.isEmpty() || maxWidth <= 0) return size
        measure.typeface = if (bold) boldFace else plainFace
        measure.textSize = size
        val wNeeded = measure.measureText(text)
        return if (wNeeded <= maxWidth) size else size * maxWidth / wNeeded * 0.98f
    }

    private fun barColour(state: PageState, watts: Int?): Int =
        state.zoneIndex(watts)?.let { colourFor(it) } ?: if (watts != null) INK else NO_TARGET

    // ---- Arc gauge -------------------------------------------------------------------------

    /** Shallow arc like Garmin's: grey track, target range in its zone colour, needle at 3s power. */
    // ---- Image caching ----------------------------------------------------------------------
    // Each update has to send both images, but they only need *drawing* again when what they show
    // changes. The renderer lives as long as the field is on screen, so these survive between updates.

    private var arcKey: List<Any?>? = null
    private var arcBmp: Bitmap? = null
    private var graphKey: List<Any?>? = null
    private var graphBmp: Bitmap? = null
    private var graphDrawnAt = 0L

    /** Redrawn only when the power, the target range or its zone changes. */
    internal fun arcImage(state: PageState, width: Int, height: Int): Bitmap {
        val key = listOf(width, height, state.power3s, state.range, state.zoneIndex(state.target), state.zoneTops)
        val cached = arcBmp
        if (cached != null && key == arcKey) return cached
        return drawArc(state, width, height).also { arcBmp = it; arcKey = key }
    }

    /** Redrawn straight away when the step, status or plan changes; otherwise at most every 5 s. */
    internal fun graphImage(state: PageState, width: Int, height: Int): Bitmap {
        val key = listOf(width, height, state.status, state.stepIndex, System.identityHashCode(state.plan),
            state.ftp, state.zoneTops, state.planNote, state.workoutActive)
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = graphBmp
        if (cached != null && key == graphKey && now - graphDrawnAt < GRAPH_REDRAW_MS) return cached
        graphDrawnAt = now
        return drawGraph(state, width, height).also { graphBmp = it; graphKey = key }
    }

    /** A half-size image, with the canvas scaled so drawing code works in full-size coordinates. */
    private fun halfSize(width: Int, height: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(max(1, (width * IMAGE_SCALE).toInt()), max(1, (height * IMAGE_SCALE).toInt()), Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(bmp.width.toFloat() / width, bmp.height.toFloat() / height)
        return bmp to c
    }

    private fun drawArc(state: PageState, width: Int, height: Int): Bitmap {
        val (bmp, c) = halfSize(width, height)

        val stroke = height * 0.34f
        val chord = width * 0.84f
        val sag = height - stroke * 1.1f
        val r = (chord * chord / 4 + sag * sag) / (2 * sag)
        val cx = width / 2f
        val cy = stroke * 0.6f + r
        val half = Math.toDegrees(asin((chord / 2 / r).toDouble())).toFloat()
        val start = 270f - half
        val sweep = 2 * half
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)

        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = TRACK
        c.drawArc(oval, start, sweep, false, paint)

        val rng = state.range ?: return bmp
        val mid = (rng.first + rng.last) / 2f
        val span = max(max((rng.last - rng.first) * 1.6f, mid * 0.3f), 40f)
        fun angle(v: Float) = start + ((v - (mid - span)) / (2 * span)).coerceIn(0f, 1f) * sweep

        paint.color = state.zoneIndex(state.target ?: mid.toInt())?.let { colourFor(it) } ?: Color.parseColor("#4fbc4f")
        val a0 = angle(rng.first.toFloat())
        c.drawArc(oval, a0, angle(rng.last.toFloat()) - a0, false, paint)

        state.power3s?.let { p ->
            val a = Math.toRadians(angle(p.toFloat()).toDouble())
            // Solid black needle with a thin white edge so it stands out on the grey track and on the band.
            val inner = r - stroke * 0.85f
            val outer = r + stroke * 0.85f
            val x0 = cx + inner * cos(a).toFloat(); val y0 = cy + inner * sin(a).toFloat()
            val x1 = cx + outer * cos(a).toFloat(); val y1 = cy + outer * sin(a).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.WHITE
            paint.strokeWidth = stroke * 0.62f + 4f
            c.drawLine(x0, y0, x1, y1, paint)
            paint.color = INK
            paint.strokeWidth = stroke * 0.62f
            c.drawLine(x0, y0, x1, y1, paint)
        }
        return bmp
    }

    // ---- Workout graph ---------------------------------------------------------------------

    private fun drawGraph(state: PageState, width: Int, height: Int): Bitmap {
        val (bmp, c) = halfSize(width, height)
        paint.reset(); paint.isAntiAlias = true

        val cur = state.stepIndex
        val widths = GraphLayout.widths(state)
        val total = widths.sum()
        val baseY = height - 1.5f

        if (state.plan == null || cur == null || total <= 0) {
            drawNote(c, state.planNote ?: "No workout", width, height)
            paint.color = TRACK; paint.strokeWidth = 2f
            c.drawLine(0f, baseY, width.toFloat(), baseY, paint)
            return bmp
        }

        // Time window: zoomed on the current step (80% of the width) with a slice of the previous and
        // next step either side (10% each), all on one time scale. Stays on the last step once finished.
        val plan = state.plan
        val starts = IntArray(widths.size)
        for (i in 1 until widths.size) starts[i] = starts[i - 1] + widths[i - 1]
        val d = max(widths[cur], 10).toFloat()
        val pad = d * EDGE / (1 - 2 * EDGE)
        val winStart = starts[cur] - pad
        val winEnd = starts[cur] + d + pad
        val xs = width / (winEnd - winStart)
        fun x(sec: Float) = (sec - winStart) * xs
        fun visible(i: Int) = starts[i] + widths[i] >= winStart && starts[i] <= winEnd

        // Vertical scale: room for FTP and for the targets and power that are on screen.
        var top = (state.ftp ?: 250) * 1.25f
        for (i in widths.indices) if (visible(i)) {
            plan?.getOrNull(i)?.let { st -> listOfNotNull(st.lo, st.hi).forEach { top = max(top, it * 1.08f) } }
            state.samples[i]?.forEach { if (it > top) top = it.toFloat() }
        }
        state.targetHigh?.let { top = max(top, it * 1.08f) }
        top = min(top, (state.ftp ?: 250) * 2.6f)
        val ys = (height - 4f) / top
        fun y(w: Int) = (baseY - w * ys).coerceIn(1f, baseY)

        // Step blocks
        for (i in widths.indices) {
            if (!visible(i) || widths[i] <= 0) continue
            val step = plan?.getOrNull(i)
                ?: if (i == cur) PlanStep(0, state.targetLow ?: state.target, state.targetHigh ?: state.target) else null
            if (step != null) {
                val x0 = x(starts[i].toFloat())
                val x1 = max(x(starts[i] + widths[i].toFloat()), x0 + 2f)
                paint.style = Paint.Style.FILL
                val colour = state.zoneIndex(step.mid)?.let { colourFor(it) } ?: NO_TARGET
                paint.color = blend(colour, if (i == cur) 0.85f else 0.55f)
                val path = Path()
                val (y0, y1) = if (step.ramp) y(step.lo ?: 0) to y(step.hi ?: 0) else y(step.mid ?: (top * 0.08f).toInt()).let { it to it }
                path.moveTo(x0, baseY); path.lineTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x1, baseY); path.close()
                c.drawPath(path, paint)
            }
        }

        // FTP line
        state.ftp?.let { f ->
            paint.style = Paint.Style.STROKE
            paint.color = Color.parseColor("#555555")
            paint.strokeWidth = 1.5f
            paint.pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
            c.drawLine(0f, y(f), width.toFloat(), y(f), paint)
            paint.pathEffect = null
        }

        // Power ridden: each step's samples laid over its block, averaged per pixel column.
        paint.style = Paint.Style.STROKE
        paint.color = TRACE
        paint.strokeWidth = 2f
        paint.strokeJoin = Paint.Join.ROUND
        val perPx = max(1, (1f / (xs * IMAGE_SCALE)).toInt()) // one point per pixel of the half-size image
        for (i in widths.indices) {
            val a = state.samples[i]
            if (a == null || a.isEmpty() || i > cur || !visible(i)) continue
            val path = Path()
            var penDown = false
            var j = 0
            while (j < a.size) {
                val px = x(starts[i] + j + perPx / 2f)
                if (px < -4f || px > width + 4f) { penDown = false; j += perPx; continue }
                var sum = 0; var n = 0
                for (k in j until min(a.size, j + perPx)) if (a[k] >= 0) { sum += a[k]; n++ }
                if (n > 0) {
                    val py = y(sum / n)
                    if (penDown) path.lineTo(px, py) else path.moveTo(px, py)
                    penDown = true
                } else {
                    penDown = false // no power reading: leave a gap
                }
                j += perPx
            }
            c.drawPath(path, paint)
        }

        // Where we are now
        val finished = state.status == WorkoutEngine.Status.FINISHED
        val nowX = x(starts[cur] + if (finished) widths[cur].toFloat() else state.stepElapsedSec.toFloat())
        paint.color = INK
        paint.strokeWidth = 2.5f
        c.drawLine(nowX, 0f, nowX, height.toFloat(), paint)

        paint.color = TRACK; paint.strokeWidth = 2f
        c.drawLine(0f, baseY, width.toFloat(), baseY, paint)

        return bmp
    }

    private fun drawNote(c: Canvas, text: String, width: Int, height: Int, corner: Boolean = false) {
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = plainFace
        paint.color = Color.parseColor("#555555")
        var size = min(height * (if (corner) 0.2f else 0.26f), 22f)
        paint.textSize = size
        while (paint.measureText(text) > width * 0.96f && size > 9f) { size -= 1f; paint.textSize = size }
        if (corner) {
            c.drawText(text, 4f, size + 2f, paint)
        } else {
            c.drawText(text, (width - paint.measureText(text)) / 2f, height / 2f + size / 3f, paint)
        }
    }

    /** Colour mixed with white, so blocks sit behind the trace. */
    private fun blend(colour: Int, amount: Float): Int {
        fun ch(v: Int) = (255 - (255 - v) * amount).toInt()
        return Color.rgb(ch(Color.red(colour)), ch(Color.green(colour)), ch(Color.blue(colour)))
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    companion object {
        private val INK = Color.parseColor("#111111")
        private val TRACK = Color.parseColor("#D9D9D9")
        private val TRACE = Color.parseColor("#C62828")
        private val NO_TARGET = Color.parseColor("#BDBDBD")
        /** The Karoo's own condensed font; must match fontFamily in styles.xml. */
        private const val KAROO_FONT = "ibm-plex-sans-condensed"
        /** The arc and graph are drawn at half size and scaled up by the Karoo: a quarter of the data. */
        private const val IMAGE_SCALE = 0.5f
        /** The graph changes slowly (the "now" line moves ~1 px every few seconds), so redraw it at most this often. */
        private const val GRAPH_REDRAW_MS = 5000L
        /** Share of the graph width given to the previous and to the next step when zoomed in. */
        private const val EDGE = 0.10f
        private val ZONE_COLOURS = PageState.ICU_ZONE_COLOURS.map { Color.parseColor(it) }

        /** Zone colour, or black when the rider's zones aren't known yet. */
        fun colourFor(zoneIndex: Int?): Int =
            if (zoneIndex == null) INK else ZONE_COLOURS[zoneIndex.coerceIn(0, ZONE_COLOURS.lastIndex)]
    }
}

/** How many seconds of the graph each step gets. */
object GraphLayout {
    fun widths(state: PageState): IntArray {
        val cur = state.stepIndex ?: return IntArray(0)
        val plan = state.plan
        val n = plan?.size ?: (cur + 1)
        if (n <= 0 || cur >= n) return IntArray(0)
        val remaining = state.intervalRemainingSec?.takeIf { it >= 0 }?.toInt() ?: 0
        val finished = state.status == WorkoutEngine.Status.FINISHED
        return IntArray(n) { i ->
            val planned = plan?.getOrNull(i)?.durationSec?.takeIf { it > 0 } ?: 60
            when {
                i < cur || finished -> state.samples[i]?.size ?: if (plan != null) planned else 0
                i == cur -> if (plan != null) max(planned, state.stepElapsedSec) else state.stepElapsedSec + remaining
                else -> planned
            }
        }
    }
}
