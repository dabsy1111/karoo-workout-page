package com.dan.workoutpage

import kotlin.math.roundToInt

/**
 * Runs a workout: which step you're on and how long you've been in it.
 * Time only counts while the ride is recording and the workout isn't paused.
 * Pure logic with no Android in it, so it's unit-tested directly.
 */
class WorkoutEngine(val name: String, val steps: List<PlanStep>) {

    enum class Status { READY, RUNNING, PAUSED, FINISHED }

    /** What happened on a tick or a button press, for beeps, alerts and laps. */
    sealed class Event {
        data class StepStarted(val index: Int, val manual: Boolean) : Event()
        data class Countdown(val secondsLeft: Int) : Event()
        /** A "press lap" step has reached its planned time and is waiting for the lap button. */
        object LapDue : Event()
        object Finished : Event()
    }

    var status = Status.READY
        private set
    var index = 0
        private set
    var elapsed = 0
        private set

    val step: PlanStep? get() = steps.getOrNull(index)

    /** Seconds left in a timed step; negative once a lap-press step has run past its planned time. */
    val remaining: Int? get() = step?.durationSec?.takeIf { it > 0 }?.let { it - elapsed }

    /** Current target watts; ramps move linearly from start to end. */
    val target: Int?
        get() {
            val s = step ?: return null
            val lo = s.lo ?: return null
            val hi = s.hi ?: return null
            if (!s.ramp || s.durationSec <= 0) return (lo + hi) / 2
            val f = (elapsed.toDouble() / s.durationSec).coerceIn(0.0, 1.0)
            return (lo + (hi - lo) * f).roundToInt()
        }

    /**
     * Range to ride in: the step's range, or ±3% around a single target (e.g. "97%" = 243 W → 236–250)
     * or around a ramp's moving target. Without this a single target would draw a zero-width band.
     */
    val range: IntRange?
        get() {
            val s = step ?: return null
            val t = target ?: return null
            if (s.ramp || s.lo == s.hi) return (t * 0.97).roundToInt()..(t * 1.03).roundToInt()
            return minOf(s.lo!!, s.hi!!)..maxOf(s.lo, s.hi)
        }

    fun start(): List<Event> {
        if (status != Status.READY || steps.isEmpty()) return emptyList()
        status = Status.RUNNING
        index = 0
        elapsed = 0
        return listOf(Event.StepStarted(0, manual = true))
    }

    fun togglePause() {
        status = when (status) {
            Status.RUNNING -> Status.PAUSED
            Status.PAUSED -> Status.RUNNING
            else -> status
        }
    }

    fun next(manual: Boolean = true): List<Event> {
        if (status != Status.RUNNING && status != Status.PAUSED) return emptyList()
        return goTo(index + 1, manual)
    }

    /** Back to the previous step, or to the start of this one if you're more than 10 s in (like a music player). */
    fun back(): List<Event> {
        if (status == Status.READY) return emptyList()
        if (status == Status.FINISHED) {
            status = Status.RUNNING
            return goTo(steps.lastIndex, true)
        }
        return goTo(if (elapsed > 10 || index == 0) index else index - 1, true)
    }

    /** The rider pressed the Karoo lap button: ends a "press lap" step. */
    fun lapPressed(): List<Event> =
        if (status == Status.RUNNING && step?.untilLap == true) next(manual = true) else emptyList()

    /** Once a second. */
    fun tick(recording: Boolean): List<Event> {
        if (status != Status.RUNNING || !recording) return emptyList()
        elapsed++
        val s = step ?: return emptyList()
        val left = s.durationSec - elapsed
        if (s.untilLap) return if (s.durationSec > 0 && left == 0) listOf(Event.LapDue) else emptyList()
        if (s.durationSec <= 0) return emptyList()
        return when {
            left <= 0 -> goTo(index + 1, manual = false)
            left <= 3 -> listOf(Event.Countdown(left))
            else -> emptyList()
        }
    }

    private fun goTo(i: Int, manual: Boolean): List<Event> {
        if (i >= steps.size) {
            status = Status.FINISHED
            elapsed = 0
            return listOf(Event.Finished)
        }
        index = i.coerceAtLeast(0)
        elapsed = 0
        return listOf(Event.StepStarted(index, manual))
    }

    /** For carrying on after the extension restarts mid-ride. */
    fun restore(status: Status, index: Int, elapsed: Int) {
        this.status = status
        this.index = index.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
        this.elapsed = elapsed.coerceAtLeast(0)
    }
}
