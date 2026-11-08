package com.dan.workoutpage

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutPlanTest {
    // Shape of intervals.icu's /events?resolve=true response (fields per the intervals.icu API forum docs).
    private val body = """
    [
      {"id": 1, "category": "WORKOUT", "type": "Ride", "name": "Sprints",
       "workout_doc": {"ftp": 250, "steps": [
         {"duration": 900, "warmup": true, "ramp": true, "power": {"start": 50, "end": 65, "units": "%ftp"}, "_power": {"start": 125, "end": 162.5}},
         {"reps": 3, "steps": [
            {"duration": 10, "power": {"start": 180, "end": 205, "units": "%ftp"}, "_power": {"start": 452, "end": 512}},
            {"duration": 120, "power": {"start": 0, "end": 49, "units": "%ftp"}, "_power": {"start": 1, "end": 123}}
         ]},
         {"duration": 600, "until_lap_press": true, "power": {"value": 55, "units": "%ftp"}},
         {"duration": 300, "freeride": true}
       ]}},
      {"id": 2, "category": "WORKOUT", "type": "Run", "name": "Run", "workout_doc": {"steps": [{"duration": 60}]}},
      {"id": 3, "category": "NOTE", "name": "Note"}
    ]
    """.trimIndent()

    @Test fun parsesRidesOnlyAndExpandsRepeats() {
        val ws = WorkoutPlan.parseEvents(body)
        assertEquals(1, ws.size)
        val w = ws[0]
        assertEquals("Sprints", w.name)
        assertEquals(9, w.expanded.size) // warmup + 3x2 + lap step + free ride
    }

    @Test fun readsWattsRampsLapAndFreeRide() {
        val s = WorkoutPlan.parseEvents(body)[0].expanded
        assertEquals(PlanStep(900, 125, 163, ramp = true), s[0]) // 162.5 rounds to 163
        assertEquals("125→163 W", s[0].rangeText())
        assertEquals(PlanStep(10, 452, 512), s[1])
        assertEquals("452–512 W", s[1].rangeText())
        assertEquals(PlanStep(120, 1, 123), s[6])
        // No resolved watts: falls back to %FTP x workout FTP.
        assertEquals(PlanStep(600, 138, 138, untilLap = true), s[7])
        assertEquals("138 W", s[7].rangeText())
        assertEquals("Free ride", s[8].rangeText())
    }

    @Test fun readsIntensityFieldOnSteps() {
        val body = """[{"category":"WORKOUT","type":"Ride","name":"T","workout_doc":{"steps":[
            {"duration":600,"intensity":"warmup","_power":{"value":140}},
            {"reps":2,"steps":[{"duration":1200,"intensity":"interval","_power":{"value":243}},
                               {"duration":300,"intensity":"recovery","_power":{"value":145}}]},
            {"duration":600,"intensity":"cooldown","_power":{"value":138}}]}}]"""
        val flags = WorkoutPlan.parseEvents(body).single().expanded.map { it.isInterval }
        assertEquals(listOf(false, true, false, true, false, false), flags)
    }

    // Dan's sweet-spot session as intervals.icu writes it, with notes above the steps.
    private val sweetSpotText = """
        Hard day 2 of 2. 3x18 at 220-228W. ERG off.

        Fueling: ~140g sucrose.

        Warmup
        - Press lap 15m 54% intensity=warmup

        Main Set 3x
        - 18m 90% intensity=interval
        - Press lap 6m 58% intensity=recovery

        Cooldown
        - 10m 54% intensity=cooldown
    """.trimIndent()

    @Test fun readsIntervalsFromWorkoutText() {
        assertEquals(listOf(false, true, false, true, false, true, false, false), WorkoutPlan.textIntervalFlags(sweetSpotText))
    }

    @Test fun usesTextWhenStepsHaveNoIntensity() {
        val q = "\""
        val desc = sweetSpotText.replace("\n", "\\n")
        val step = { d: Int, w: Int -> "{${q}duration$q:$d,${q}_power$q:{${q}value$q:$w}}" }
        val body = "[{${q}category$q:${q}WORKOUT$q,${q}type$q:${q}Ride$q,${q}name$q:${q}SS$q,${q}description$q:$q$desc$q," +
            "${q}workout_doc$q:{${q}steps$q:[${step(900, 135)},{${q}reps$q:3,${q}steps$q:[${step(1080, 225)},${step(360, 145)}]},${step(600, 135)}]}}]"
        val w = WorkoutPlan.parseEvents(body).single()
        assertEquals(listOf(false, true, false, true, false, true, false, false), w.expanded.map { it.isInterval })
    }

    @Test fun textThatDoesntLineUpIsIgnored() {
        val body = "[{\"category\":\"WORKOUT\",\"type\":\"Ride\",\"name\":\"X\",\"description\":\"- 5m 50% intensity=interval\"," +
            "\"workout_doc\":{\"steps\":[{\"duration\":300},{\"duration\":300}]}}]"
        assertEquals(listOf(false, false), WorkoutPlan.parseEvents(body).single().expanded.map { it.isInterval })
    }

    @Test fun scalingFollowsKarooTargets() {
        assertEquals(PlanStep(10, 470, 532), PlanStep(10, 452, 512).scaled(1.04))
        assertEquals(PlanStep(10, null, null), PlanStep(10, null, null).scaled(1.1))
    }
}
