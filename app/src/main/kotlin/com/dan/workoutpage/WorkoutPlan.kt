package com.dan.workoutpage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/**
 * One step of a planned workout, in watts.
 * For a ramp, [lo] is the start and [hi] the end (hi can be lower for a ramp down).
 * Null watts = no power target (free ride, HR-only step).
 */
data class PlanStep(
    val durationSec: Int,
    val lo: Int?,
    val hi: Int?,
    val ramp: Boolean = false,
    val untilLap: Boolean = false,
    /** Marked "intensity=interval" in intervals.icu: one of the efforts the post-workout summary covers. */
    val isInterval: Boolean = false,
) {
    val mid: Int? get() = if (lo != null && hi != null) (lo + hi) / 2 else lo ?: hi

    fun scaled(f: Double): PlanStep =
        if (f == 1.0) this else copy(lo = lo?.let { (it * f).roundToInt() }, hi = hi?.let { (it * f).roundToInt() })

    fun rangeText(): String = when {
        lo == null || hi == null -> "Free ride"
        ramp -> "$lo→$hi W"
        lo == hi -> "$lo W"
        else -> "$lo–$hi W"
    }
}

data class IcuWorkout(
    val name: String,
    /** Repeats written out: the steps in the order they're ridden. */
    val expanded: List<PlanStep>,
)

object WorkoutPlan {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses intervals.icu's /events response (called with resolve=true) into workouts with steps. */
    fun parseEvents(body: String): List<IcuWorkout> {
        val root = json.parseToJsonElement(body)
        val events = (root as? JsonArray) ?: return emptyList()
        return events.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.str("category") != null && o.str("category") != "WORKOUT") return@mapNotNull null
            val type = o.str("type")
            if (type != null && !type.contains("Ride")) return@mapNotNull null
            if (o.str("start_date_local")?.startsWith(PlanRepository.today()) == false) return@mapNotNull null
            val doc = o["workout_doc"] as? JsonObject ?: return@mapNotNull null
            val steps = doc["steps"] as? JsonArray ?: return@mapNotNull null
            val ftp = doc.num("ftp")
            var expanded = flatten(steps, ftp)
            // If the steps didn't carry an intensity, read "intensity=interval" from the workout text.
            if (!hasIntensity(steps)) {
                o.str("description")?.let { textIntervalFlags(it) }?.takeIf { it.size == expanded.size }?.let { flags ->
                    expanded = expanded.mapIndexed { i, st -> st.copy(isInterval = flags[i]) }
                }
            }
            IcuWorkout(name = o.str("name") ?: "Workout", expanded = expanded)
        }
    }

    private fun flatten(steps: JsonArray, ftp: Double?): List<PlanStep> {
        val out = mutableListOf<PlanStep>()
        for (s in steps) {
            val o = s as? JsonObject ?: continue
            val inner = o["steps"] as? JsonArray
            if (inner != null) {
                val reps = (o.num("reps")?.toInt() ?: 1).coerceIn(1, 100)
                val block = flatten(inner, ftp)
                repeat(reps) { out += block }
            } else {
                out += step(o, ftp)
            }
        }
        return out
    }

    private fun step(o: JsonObject, ftp: Double?): PlanStep {
        val ramp = o.bool("ramp") == true
        val untilLap = o.bool("until_lap_press") == true
        val duration = o.num("duration")?.roundToInt() ?: 0
        if (o.bool("freeride") == true) return PlanStep(duration, null, null, untilLap = untilLap)

        // Prefer intervals.icu's resolved watts; fall back to working them out from %FTP.
        val resolved = o["_power"] as? JsonObject
        var lo: Double? = null
        var hi: Double? = null
        if (resolved != null) {
            lo = resolved.num("start") ?: resolved.num("value")
            hi = resolved.num("end") ?: resolved.num("value")
        } else {
            val p = o["power"] as? JsonObject
            if (p != null) {
                val units = p.str("units") ?: "%ftp"
                val f: Double? = when (units.lowercase()) {
                    "w", "watts" -> 1.0
                    "%ftp" -> ftp?.let { it / 100.0 }
                    else -> null
                }
                if (f != null) {
                    lo = (p.num("start") ?: p.num("value"))?.times(f)
                    hi = (p.num("end") ?: p.num("value"))?.times(f)
                }
            }
        }
        return PlanStep(duration, lo?.roundToInt(), hi?.roundToInt(), ramp, untilLap, isInterval(o))
    }

    private fun isInterval(o: JsonObject) = o.str("intensity")?.equals("interval", ignoreCase = true) == true

    private fun hasIntensity(steps: JsonArray): Boolean = steps.any { s ->
        val o = s as? JsonObject ?: return@any false
        o.str("intensity") != null || (o["steps"] as? JsonArray)?.let { hasIntensity(it) } == true
    }

    /**
     * Which steps say "intensity=interval", read from intervals.icu's workout text, e.g.
     *   Main Set 3x
     *   - 18m 90% intensity=interval
     *   - Press lap 6m 58% intensity=recovery
     * Step lines start with "-"; a heading ending in "Nx" repeats the lines under it, up to the next blank line.
     */
    fun textIntervalFlags(text: String): List<Boolean> {
        val out = mutableListOf<Boolean>()
        var reps = 1
        val block = mutableListOf<Boolean>()
        fun flush() { repeat(reps) { out += block }; block.clear(); reps = 1 }
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("-") -> block += line.contains("intensity=interval", ignoreCase = true)
                else -> {
                    flush()
                    reps = Regex("""(\d+)\s*x$""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 1
                }
            }
        }
        flush()
        return out
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
}
