package com.dan.workoutpage

/**
 * Records power once a second, filed under the workout step it belongs to.
 * Gives the current step's average and max, and the trace for the workout graph.
 * Skips and rewinds work: re-entering a step starts that step's record again.
 */
class WorkoutTracker {
    var stepIndex: Int = -1
        private set

    /** Last completed run of each step. */
    private val done = HashMap<Int, IntArray>()
    private var current = IntArray(600)
    private var n = 0

    private var sum = 0L
    private var count = 0
    private var max = -1

    /**
     * Call once a second.
     * @param step 0-based current step, or null when no workout is playing.
     * @param recording true while the ride is recording (not paused or stopped).
     * @param power latest 1 s power, or null if there's no reading.
     */
    fun tick(step: Int?, recording: Boolean, power: Int?) {
        if (step == null) return
        if (step != stepIndex) enter(step)
        if (!recording) return
        val p = power?.coerceIn(0, 3000) ?: -1
        if (n == current.size) current = current.copyOf(current.size * 2)
        current[n++] = p
        if (p >= 0) {
            sum += p
            count++
            if (p > max) max = p
        }
    }

    private fun enter(step: Int) {
        if (stepIndex >= 0) done[stepIndex] = current.copyOf(n)
        stepIndex = step
        n = 0
        sum = 0
        count = 0
        max = -1
    }

    /** New ride: forget everything. */
    fun reset() {
        done.clear()
        stepIndex = -1
        n = 0
        sum = 0
        count = 0
        max = -1
    }

    val elapsedSec: Int get() = n
    val avg: Int? get() = if (count > 0) Math.round(sum.toDouble() / count).toInt() else null
    val maxPower: Int? get() = max.takeIf { it >= 0 }

    /** Snapshot for drawing: completed steps plus a copy of the current one. */
    fun samples(): Map<Int, IntArray> {
        val m = HashMap<Int, IntArray>(done)
        if (stepIndex >= 0) m[stepIndex] = current.copyOf(n)
        return m
    }
}
