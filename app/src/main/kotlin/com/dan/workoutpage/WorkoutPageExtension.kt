package com.dan.workoutpage

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class WorkoutPageExtension : KarooExtension("workout-page", "3.0") {

    private lateinit var karooSystem: KarooSystemService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var live: LiveWorkout

    override val types by lazy {
        listOf(WorkoutPageDataType(karooSystem, extension, live))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        live = LiveWorkout(karooSystem, PlanRepository(this), scope)
        karooSystem.connect { connected -> if (connected) live.start() }
    }

    override fun onDestroy() {
        live.stop()
        scope.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}

/** Live values of one of Karoo's own data types. */
fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> = callbackFlow {
    val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
        trySend(event.state)
    }
    awaitClose { removeConsumer(listenerId) }
}
