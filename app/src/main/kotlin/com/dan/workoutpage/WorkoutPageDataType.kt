package com.dan.workoutpage

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** The Workout Page field. All the live tracking happens in [LiveWorkout]; this just draws it. */
class WorkoutPageDataType(
    private val karooSystem: KarooSystemService,
    extension: String,
    private val live: LiveWorkout,
) : DataTypeImpl(extension, "workout-page") {

    /** The field's own numeric value is 3s power, so it still means something if Karoo ever shows it as a number. */
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.SMOOTHED_3S_AVERAGE_POWER).collect { state ->
                if (state is StreamState.Streaming) {
                    val v = state.dataPoint.singleValue ?: 0.0
                    emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to v))))
                } else {
                    emitter.onNext(state)
                }
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val renderer = PageRenderer(context)
        val (width, height) = config.viewSize
        // Our field draws its own labels, so hide Karoo's field header.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        if (config.preview) {
            emitter.updateView(renderer.render(PageState.PREVIEW, width, height))
            return
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            // LiveWorkout publishes once a second, and straight away after a button tap.
            // karoo-ext drops view updates less than ~900 ms apart, so space them out rather than lose a tap's update.
            var last = 0L
            live.state.collect {
                val wait = 950 - (System.currentTimeMillis() - last)
                if (wait > 0) delay(wait)
                try {
                    emitter.updateView(renderer.render(live.state.value, width, height))
                    last = System.currentTimeMillis()
                } catch (e: Exception) {
                    Timber.w(e, "render failed")
                }
            }
        }
        emitter.setCancellable { scope.cancel() }
    }
}
