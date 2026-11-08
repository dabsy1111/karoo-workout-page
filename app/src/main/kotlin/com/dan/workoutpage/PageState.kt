package com.dan.workoutpage

import kotlin.math.roundToInt

/** Everything the page shows, already reduced to plain numbers. Null means "no value yet". */
data class PageState(
    val power3s: Int? = null,
    /** Karoo's live target for the current step. */
    val target: Int? = null,
    val targetLow: Int? = null,
    val targetHigh: Int? = null,
    val intervalRemainingSec: Long? = null,
    /** 1-based step number for display. */
    val step: Int? = null,
    val stepCount: Int? = null,
    /** Upper watt limit of each power zone except the last (Z1..Z6 for a 7-zone model). Empty = unknown. */
    val zoneTops: List<Int> = emptyList(),
    val ftp: Int? = null,
    /** Average and max power since the current step started (recording time only). */
    val stepAvg: Int? = null,
    val stepMax: Int? = null,
    val stepElapsedSec: Int = 0,
    /** Every step of the workout from intervals.icu, matched to Karoo's steps and scaled to Karoo's targets. */
    val plan: List<PlanStep>? = null,
    /** Why there's no plan (no key, offline, steps didn't match...). */
    val planNote: String? = null,
    /** Power ridden, 1 sample/s, per step index (latest run of each step). -1 = no reading. */
    val samples: Map<Int, IntArray> = emptyMap(),
    /** Null when there's no workout to run. */
    val status: WorkoutEngine.Status? = null,
    val workoutName: String? = null,
    /** How many ride workouts intervals.icu has today (tap the name to switch before starting). */
    val workoutCount: Int = 0,
    val workoutChoice: Int = 0,
) {
    /** A workout is under way (running or paused). */
    val workoutActive: Boolean
        get() = status == WorkoutEngine.Status.RUNNING || status == WorkoutEngine.Status.PAUSED

    /** 0-based index of the current step. */
    val stepIndex: Int? get() = step?.minus(1)

    /** Range used for colouring: the workout's own range, or target ±5% when only a single value is given. */
    val range: IntRange?
        get() {
            if (targetLow != null && targetHigh != null && targetHigh >= targetLow && targetHigh > 0) {
                return targetLow..targetHigh
            }
            val t = target ?: return null
            if (t <= 0) return null
            return (t * 0.95).roundToInt()..(t * 1.05).roundToInt()
        }

    /** The steps after the current one, from the intervals.icu plan. */
    fun upcoming(n: Int): List<PlanStep> {
        val p = plan ?: return emptyList()
        val i = stepIndex ?: return emptyList()
        return p.drop(i + 1).take(n)
    }

    /** Planned length of the current step, if known. */
    val stepPlannedSec: Int?
        get() = stepIndex?.let { plan?.getOrNull(it)?.durationSec }?.takeIf { it > 0 }

    /** Watt range of a zone (0 = Z1), from the rider's zone limits. */
    fun zoneRange(zone: Int): IntRange? {
        if (zone < 0 || zone >= zoneTops.size) return null
        val lo = if (zone == 0) 0 else zoneTops[zone - 1] + 1
        return lo..zoneTops[zone]
    }

    /**
     * Z1 and Z2 steps ride to the whole zone, whatever range the workout gives;
     * harder steps keep the workout's range. Null for steps with no target.
     */
    fun easyZoneRange(watts: Int?): IntRange? {
        val z = zoneIndex(watts) ?: return null
        return if (z <= 1) zoneRange(z) else null
    }

    /** Range text for a planned step, e.g. "139–188 W" for a Z2 step or "236–250 W" for a threshold one. */
    fun rangeText(step: PlanStep): String =
        easyZoneRange(step.mid)?.let { "${it.first}–${it.last} W" } ?: step.rangeText()

    /** 0-based power zone for these watts (0 = Z1), or null if the zones or the value are unknown. */
    fun zoneIndex(watts: Int?): Int? {
        val w = watts ?: return null
        if (zoneTops.isEmpty()) return null
        val i = zoneTops.indexOfFirst { w <= it }
        return if (i == -1) zoneTops.size else i
    }

    companion object {
        /**
         * Zone colours Z1..Z7, Dan's intervals.icu palette with three changes for reading in sun:
         * Z3 darkened from #ffdb59 to gold #c99400 and Z4 from #ffa759 to #e06400 (both were too pale on white),
         * and Z7 red instead of intervals.icu's grey so the hardest efforts don't look like "no data".
         */
        val ICU_ZONE_COLOURS = listOf("#4fbca7", "#4fbc4f", "#c99400", "#e06400", "#e85280", "#9572dc", "#d32f2f")

        /** Coggan upper limits as % of FTP, intervals.icu's default 7-zone model. */
        private val DEFAULT_ZONE_TOPS_PCT = listOf(55, 75, 90, 105, 120, 150)

        /**
         * Zone limits from the Karoo rider profile. Uses Karoo's own power zones when there are
         * 7 of them, otherwise the default percentages of FTP.
         */
        fun zoneTops(ftp: Int, zones: List<Pair<Int, Int>>): List<Int> {
            if (zones.size == ICU_ZONE_COLOURS.size) {
                return zones.dropLast(1).map { it.second }
            }
            if (ftp <= 0) return emptyList()
            return DEFAULT_ZONE_TOPS_PCT.map { (ftp * it / 100.0).roundToInt() }
        }

        /** A made-up mid-workout state for Karoo's field picker and for tests. */
        val PREVIEW: PageState by lazy {
            val plan = listOf(
                PlanStep(900, 120, 160, ramp = true),
                PlanStep(10, 452, 512), PlanStep(120, 1, 123),
                PlanStep(10, 452, 512), PlanStep(120, 1, 123),
                PlanStep(10, 452, 512), PlanStep(600, 130, 145),
            )
            fun wave(n: Int, base: Int, amp: Int, seed: Int) = IntArray(n) { base + ((it * 7 + seed) % 23 - 11) * amp / 11 }
            PageState(
                power3s = 138, target = 123, targetLow = 104, targetHigh = 142,
                intervalRemainingSec = 74, step = 3, stepCount = plan.size,
                zoneTops = zoneTops(250, emptyList()), ftp = 250,
                stepAvg = 132, stepMax = 147, stepElapsedSec = 46,
                plan = plan,
                samples = mapOf(0 to wave(900, 140, 25, 1), 1 to wave(11, 480, 30, 2), 2 to wave(46, 132, 15, 3)),
                status = WorkoutEngine.Status.RUNNING, workoutName = "Sprints 3x10s",
            )
        }
    }
}

object Format {
    const val DASH = "--"

    /** Karoo style: 00:09, 12:30, 1:05:00. Negative (open-ended step) shows as "open". */
    fun duration(sec: Long?): String {
        if (sec == null) return DASH
        if (sec < 0) return "open"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun watts(w: Int?): String = w?.toString() ?: DASH

    fun range(r: IntRange?): String = if (r == null) "" else "${r.first}–${r.last}"

    /** Time left in the step, Garmin style: 0:09, 14:40, 1:02:00. */
    fun countdown(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Step lengths like Garmin's list: 0:10, 2:00, 15:00, 1:05:00. */
    fun stepLength(sec: Int): String {
        if (sec <= 0) return "--"
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun step(step: Int?, count: Int?): String = when {
        step == null && count == null -> DASH
        count == null -> "$step"
        step == null -> "-/$count"
        else -> "$step/$count"
    }
}
