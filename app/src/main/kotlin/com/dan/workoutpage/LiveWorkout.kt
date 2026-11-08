package com.dan.workoutpage

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.Lap
import io.hammerhead.karooext.models.MarkLap
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Runs the workout. Lives as long as the extension, so the workout keeps going and the
 * trace keeps recording while you're on other pages.
 * Everything that touches the engine or tracker runs on one thread ([engineThread]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveWorkout(
    private val karoo: KarooSystemService,
    private val repo: PlanRepository,
    private val scope: CoroutineScope,
) {
    enum class Control { START_PAUSE, NEXT, BACK, CHOOSE }

    private val _state = MutableStateFlow(PageState())
    val state: StateFlow<PageState> = _state

    private val engineThread = Dispatchers.IO.limitedParallelism(1)
    private val tracker = WorkoutTracker()
    private var engine: WorkoutEngine? = null
    private var workoutChoice = 0

    @Volatile private var power1s: DataPoint? = null
    @Volatile private var power3s: DataPoint? = null
    @Volatile private var rideState: RideState = RideState.Idle
    @Volatile private var zoneTops: List<Int> = emptyList()
    @Volatile private var ftp: Int? = null

    @Volatile private var workouts: List<IcuWorkout>? = null
    @Volatile private var fetchNote: String? = null
    private var fetchJob: Job? = null
    private var fetchAttempts = 0

    /** Our own MarkLap also comes back as a Lap event; ignore it for a moment. */
    @Volatile private var ownLapAt = 0L
    private var lastSaveAt = 0L
    private var started = false

    /** The once-a-second loop and the power listeners. Only alive while a workout is running or paused. */
    private var loopJob: Job? = null

    /**
     * Safe to call again on reconnect; only the first call starts anything.
     * Until a workout is started, nothing runs on a timer: only Karoo's ride/profile/lap events
     * (which arrive when something happens) and the once-a-day plan download.
     */
    fun start() {
        if (started) return
        started = true
        instance = this
        karoo.addConsumer<RideState> { s ->
            val ended = s is RideState.Idle && rideState !is RideState.Idle
            rideState = s
            if (ended) scope.launch(engineThread) { newRide() }
        }
        karoo.addConsumer<UserProfile> { p ->
            ftp = p.ftp.takeIf { it > 0 }
            zoneTops = PageState.zoneTops(p.ftp, p.powerZones.map { it.min to it.max })
            scope.launch(engineThread) { publish() }
        }
        karoo.addConsumer<Lap> {
            if (System.currentTimeMillis() - ownLapAt > 3000) scope.launch(engineThread) {
                handle(engine?.lapPressed().orEmpty()); syncLoop(); publish()
            }
        }

        repo.cachedToday()?.let { workouts = runCatching { WorkoutPlan.parseEvents(it) }.getOrNull() }
        scope.launch(engineThread) { ensureEngine(); syncLoop(); publish() } // picks up a run saved before a restart
        refreshPlan(manual = false)
    }

    /** Start the loop when a workout is under way, stop it as soon as it isn't. */
    private fun syncLoop() {
        val s = engine?.status
        val active = s == WorkoutEngine.Status.RUNNING || s == WorkoutEngine.Status.PAUSED
        if (active && loopJob?.isActive != true) {
            loopJob = scope.launch(engineThread) {
                launch { karoo.streamDataFlow(DataType.Type.POWER).collect { power1s = (it as? StreamState.Streaming)?.dataPoint } }
                launch { karoo.streamDataFlow(DataType.Type.SMOOTHED_3S_AVERAGE_POWER).collect { power3s = (it as? StreamState.Streaming)?.dataPoint } }
                while (isActive) {
                    runCatching { tick() }.onFailure { Timber.w(it, "tick failed") }
                    delay(1000)
                }
            }
        } else if (!active && loopJob?.isActive == true) {
            loopJob?.cancel() // also stops listening to power
            loopJob = null
            power1s = null
            power3s = null
        }
    }

    fun stop() {
        if (instance === this) instance = null
    }

    /** A button in the field was tapped. */
    fun control(c: Control) {
        scope.launch(engineThread) {
            val e = engine ?: return@launch
            val events = when (c) {
                Control.START_PAUSE -> if (e.status == WorkoutEngine.Status.READY) e.start() else { e.togglePause(); emptyList() }
                Control.NEXT -> e.next()
                Control.BACK -> e.back()
                Control.CHOOSE -> {
                    val n = workouts?.size ?: 0
                    if (e.status == WorkoutEngine.Status.READY && n > 1) {
                        workoutChoice = (workoutChoice + 1) % n
                        engine = null
                        ensureEngine()
                    }
                    emptyList()
                }
            }
            handle(events)
            save(force = true)
            syncLoop()
            publish()
        }
    }

    /** Fetch today's workouts again (after the API key changes, or to retry). */
    fun refreshPlan(manual: Boolean = true) {
        if (manual) fetchAttempts = 0
        if (fetchJob?.isActive == true) return
        fetchAttempts++
        fetchJob = scope.launch {
            // With nothing ticking before the start, retries live here: up to 10 tries a minute apart.
            while (true) {
                when (val r = repo.fetchToday(karoo)) {
                    is PlanRepository.Result.Ok -> {
                        workouts = runCatching { WorkoutPlan.parseEvents(r.body) }.getOrElse { emptyList() }
                        fetchNote = null
                    }
                    is PlanRepository.Result.Failed -> fetchNote = r.message
                }
                launch(engineThread) { ensureEngine(); publish() }.join()
                if (workouts != null || fetchAttempts >= 10 || repo.apiKey == null) break
                delay(60_000)
                fetchAttempts++
            }
        }
    }

    /** Build the engine for the chosen workout once the plan is here; pick up a saved run if there is one. */
    private fun ensureEngine() {
        val ws = workouts ?: return
        val current = engine
        if (current != null) {
            // Plan re-downloaded before starting: use the fresh copy.
            if (current.status != WorkoutEngine.Status.READY) return
            val w = ws.getOrNull(workoutChoice)
            if (w != null && w.name == current.name && w.expanded == current.steps) return
        }
        if (ws.isEmpty()) { engine = null; return }
        val saved = repo.savedRun()
        if (current == null && saved != null) workoutChoice = ws.indexOfFirst { it.name == saved.name }.takeIf { it >= 0 } ?: workoutChoice
        workoutChoice = workoutChoice.coerceIn(0, ws.lastIndex)
        val w = ws[workoutChoice]
        engine = WorkoutEngine(w.name, w.expanded).also { e ->
            if (current == null && saved != null && saved.name == w.name && saved.stepCount == w.expanded.size) {
                e.restore(saved.status, saved.index, saved.elapsed)
            }
        }
    }

    private fun newRide() {
        tracker.reset()
        repo.clearRun()
        engine = null
        workoutChoice = 0
        ensureEngine()
        syncLoop()
        publish()
    }

    private fun tick() {
        val recording = rideState is RideState.Recording
        val e = engine
        val running = e?.status == WorkoutEngine.Status.RUNNING
        val p1 = power1s?.let { it.values[DataType.Field.POWER] ?: it.singleValue }?.roundToInt()
        // File this second's power under the step it was ridden in, *then* let the engine move on,
        // so a step's last second (e.g. a sprint) doesn't land in the next step's AVG/MAX.
        if (e != null && e.status != WorkoutEngine.Status.READY && e.status != WorkoutEngine.Status.FINISHED) {
            tracker.tick(e.index, recording && running, p1)
        }
        handle(e?.tick(recording).orEmpty())
        save(force = e?.status == WorkoutEngine.Status.FINISHED)
        publish()
        syncLoop() // the workout may just have finished: stop the loop
    }

    private fun publish() {
        val e = engine
        val ws = workouts
        val active = e?.status == WorkoutEngine.Status.RUNNING || e?.status == WorkoutEngine.Status.PAUSED
        // Z1/Z2 steps: the whole zone is the range, not what the workout says.
        val range = e?.target?.let { PageState(zoneTops = zoneTops).easyZoneRange(it) } ?: e?.range
        _state.value = PageState(
            power3s = power3s?.let { it.values[DataType.Field.SMOOTHED_3S_AVERAGE_POWER] ?: it.singleValue }?.roundToInt(),
            target = e?.target.takeIf { e?.status == WorkoutEngine.Status.RUNNING || e?.status == WorkoutEngine.Status.PAUSED },
            targetLow = range?.first.takeIf { active },
            targetHigh = range?.last.takeIf { active },
            intervalRemainingSec = e?.remaining?.toLong(),
            step = e?.let { it.index + 1 },
            stepCount = e?.steps?.size,
            zoneTops = zoneTops,
            ftp = ftp,
            stepAvg = tracker.avg,
            stepMax = tracker.maxPower,
            stepElapsedSec = e?.elapsed ?: 0,
            plan = e?.steps,
            planNote = when {
                e != null -> null
                fetchNote != null -> fetchNote
                repo.apiKey == null -> "Add intervals.icu API key in the Workout Page app"
                ws == null -> "Loading intervals.icu workout…"
                else -> "No intervals.icu ride workout today"
            },
            samples = tracker.samples(),
            status = e?.status,
            workoutName = e?.name,
            workoutCount = ws?.size ?: 0,
            workoutChoice = workoutChoice,
        )
    }

    // ---- Beeps, alerts and laps --------------------------------------------------------------

    private fun handle(events: List<WorkoutEngine.Event>) {
        val e = engine ?: return
        val z = PageState(zoneTops = zoneTops)
        for (ev in events) when (ev) {
            is WorkoutEngine.Event.Countdown -> beep(listOf(2000 to 120))
            WorkoutEngine.Event.LapDue -> {
                beep(listOf(3000 to 250, null to 80, 3000 to 250, null to 80, 3000 to 250))
                val next = e.steps.getOrNull(e.index + 1)
                alert(
                    title = LAP_REMINDER,
                    detail = next?.let { "Next: ${if (it.untilLap) "until lap" else Format.stepLength(it.durationSec)} · ${z.rangeText(it)}" } ?: "Then the workout ends",
                    zone = null,
                )
            }
            // New step: lap and beep only; the field already shows the new target.
            is WorkoutEngine.Event.StepStarted -> {
                lap()
                beep(listOf(3000 to 250, null to 80, 3000 to 400))
            }
            // Workout over: no lap, beep or alert. The ride carries on untouched.
            WorkoutEngine.Event.Finished -> Unit
        }
    }

    private fun lap() {
        ownLapAt = System.currentTimeMillis()
        karoo.dispatch(MarkLap)
    }

    private fun beep(tones: List<Pair<Int?, Int>>) {
        karoo.dispatch(PlayBeepPattern(tones.map { PlayBeepPattern.Tone(it.first, it.second) }))
    }

    private fun alert(title: String, detail: String?, zone: Int?) {
        val bg = ZONE_COLOUR_RES.getOrNull(zone ?: -1) ?: R.color.alert_bg
        val fg = if (zone != null && zone <= 3) R.color.alert_text_dark else R.color.alert_text_light
        karoo.dispatch(InRideAlert("workout-step", R.drawable.ic_workout_page, title, detail, 5000L, bg, fg))
    }

    private fun save(force: Boolean) {
        val e = engine ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAt < 10_000) return
        lastSaveAt = now
        if (e.status == WorkoutEngine.Status.READY) repo.clearRun()
        else repo.saveRun(PlanRepository.SavedRun(e.name, e.steps.size, e.status, e.index, e.elapsed))
    }

    companion object {
        /** The running instance, for the settings screen and the button receiver. */
        @Volatile var instance: LiveWorkout? = null

        const val LAP_REMINDER = "Press lap to start next interval"

        private val ZONE_COLOUR_RES = listOf(R.color.zone1, R.color.zone2, R.color.zone3, R.color.zone4, R.color.zone5, R.color.zone6, R.color.zone7)
    }
}
