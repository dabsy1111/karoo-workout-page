package com.dan.workoutpage

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives taps on the field's buttons. Not exported, so only this app's own
 * PendingIntents (handed to Karoo inside the field's view) can reach it.
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = runCatching { LiveWorkout.Control.valueOf(intent.action?.substringAfterLast('.') ?: return) }.getOrNull() ?: return
        LiveWorkout.instance?.control(c)
    }

    companion object {
        fun pendingIntent(context: Context, c: LiveWorkout.Control): PendingIntent {
            val i = Intent(context, ControlReceiver::class.java).setAction("${context.packageName}.${c.name}")
            return PendingIntent.getBroadcast(context, c.ordinal, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
