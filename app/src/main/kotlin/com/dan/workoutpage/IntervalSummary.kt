package com.dan.workoutpage

import kotlin.math.pow
import kotlin.math.roundToInt

/** One row of the post-workout summary: an "intensity=interval" step as you rode it. */
data class IntervalRow(
    val number: Int,
    val seconds: Int,
    val target: PlanStep,
    val avg: Int?,
    val np: Int?,
    val max: Int?,
)

data class IntervalSummary(val rows: List<IntervalRow>, val all: IntervalRow) {
    companion object {
        /**
         * Rows for every step marked as an interval that was actually ridden (has power samples).
         * Null when the workout has no marked intervals, so the plain "Done" screen shows instead.
         */
        fun of(plan: List<PlanStep>?, samples: Map<Int, IntArray>): IntervalSummary? {
            plan ?: return null
            val rows = mutableListOf<IntervalRow>()
            val everything = mutableListOf<Int>()
            plan.forEachIndexed { i, step ->
                if (!step.isInterval) return@forEachIndexed
                val a = samples[i]?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                val valid = a.filter { it >= 0 }
                everything += valid
                rows += IntervalRow(rows.size + 1, a.size, step, avg(valid), np(a), valid.maxOrNull())
            }
            if (rows.isEmpty()) return null
            val all = IntervalRow(0, rows.sumOf { it.seconds }, rows.first().target, avg(everything), npOfRows(plan, samples), everything.maxOrNull())
            return IntervalSummary(rows, all)
        }

        private fun avg(v: List<Int>): Int? = if (v.isEmpty()) null else (v.sum().toDouble() / v.size).roundToInt()

        /**
         * Normalized power: 30 s rolling average, raised to the 4th power, averaged, 4th root.
         * Null for efforts under 30 s, where it doesn't mean much.
         */
        fun np(a: IntArray): Int? {
            val v = a.map { if (it < 0) 0 else it }
            if (v.size < 30) return null
            var sum = v.take(30).sum().toDouble()
            var acc = (sum / 30).pow(4)
            var n = 1
            for (i in 30 until v.size) {
                sum += v[i] - v[i - 30]
                acc += (sum / 30).pow(4)
                n++
            }
            return (acc / n).pow(0.25).roundToInt()
        }

        /** NP over all the interval seconds, each interval's rolling window kept to itself. */
        private fun npOfRows(plan: List<PlanStep>, samples: Map<Int, IntArray>): Int? {
            var acc = 0.0
            var n = 0
            plan.forEachIndexed { i, step ->
                if (!step.isInterval) return@forEachIndexed
                val v = samples[i]?.map { if (it < 0) 0 else it } ?: return@forEachIndexed
                if (v.size < 30) return@forEachIndexed
                var sum = v.take(30).sum().toDouble()
                acc += (sum / 30).pow(4); n++
                for (k in 30 until v.size) { sum += v[k] - v[k - 30]; acc += (sum / 30).pow(4); n++ }
            }
            return if (n == 0) null else (acc / n).pow(0.25).roundToInt()
        }
    }
}
