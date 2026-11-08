package com.dan.workoutpage

import android.content.Context
import android.util.Base64
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores the intervals.icu API key and today's workouts, and fetches them through the Karoo's connection. */
class PlanRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("workout_page", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(v) = prefs.edit().putString(KEY_API, v?.trim()).remove(KEY_BODY).remove(KEY_DATE).apply()

    /** Today's /events response if it was fetched today (survives a restart or losing signal mid-ride). */
    fun cachedToday(): String? = if (prefs.getString(KEY_DATE, null) == today()) prefs.getString(KEY_BODY, null) else null

    /** Where you were in the workout, so an extension restart mid-ride carries on. Today only. */
    data class SavedRun(val name: String, val stepCount: Int, val status: WorkoutEngine.Status, val index: Int, val elapsed: Int)

    fun saveRun(r: SavedRun) {
        prefs.edit()
            .putString(RUN, listOf(today(), r.status.name, r.index, r.elapsed, r.stepCount).joinToString("|"))
            .putString(RUN_NAME, r.name)
            .apply()
    }

    fun savedRun(): SavedRun? {
        val p = prefs.getString(RUN, null)?.split("|") ?: return null
        if (p.size != 5 || p[0] != today()) return null
        return runCatching {
            SavedRun(prefs.getString(RUN_NAME, "")!!, p[4].toInt(), WorkoutEngine.Status.valueOf(p[1]), p[2].toInt(), p[3].toInt())
        }.getOrNull()
    }

    fun clearRun() = prefs.edit().remove(RUN).remove(RUN_NAME).apply()

    sealed class Result {
        data class Ok(val body: String) : Result()
        data class Failed(val message: String) : Result()
    }

    suspend fun fetchToday(karoo: KarooSystemService): Result {
        val key = apiKey ?: return Result.Failed("Add intervals.icu API key in the Workout Page app")
        val day = today()
        val auth = Base64.encodeToString("API_KEY:$key".toByteArray(), Base64.NO_WRAP)
        val request = OnHttpResponse.MakeHttpRequest(
            method = "GET",
            url = "https://intervals.icu/api/v1/athlete/0/events?oldest=$day&newest=$day&category=WORKOUT&resolve=true",
            headers = mapOf("Authorization" to "Basic $auth", "Accept" to "application/json"),
            waitForConnection = false,
        )
        val done = withTimeoutOrNull(30_000) {
            callbackFlow {
                val id = karoo.addConsumer(request, onError = { trySend(HttpResponseState.Complete(0, emptyMap(), null, it)) }) { e: OnHttpResponse ->
                    trySend(e.state)
                }
                awaitClose { karoo.removeConsumer(id) }
            }.first { it is HttpResponseState.Complete } as HttpResponseState.Complete
        } ?: return Result.Failed("intervals.icu: no response (offline?)")

        return when {
            done.statusCode == 401 || done.statusCode == 403 -> Result.Failed("intervals.icu rejected the API key")
            done.statusCode !in 200..299 || done.body == null ->
                Result.Failed("intervals.icu: ${done.error ?: "error ${done.statusCode}"}")
            else -> {
                val body = done.body!!.decodeToString()
                prefs.edit().putString(KEY_BODY, body).putString(KEY_DATE, day).apply()
                Result.Ok(body)
            }
        }
    }

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_BODY = "today_body"
        private const val KEY_DATE = "today_date"
        private const val RUN = "run"
        private const val RUN_NAME = "run_name"

        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
