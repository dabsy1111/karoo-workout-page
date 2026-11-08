package com.dan.workoutpage

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Where the intervals.icu API key goes. Type it in, or send it over USB:
 * adb shell am start -n com.dan.workoutpage/.SettingsActivity --es api_key YOURKEY
 */
class SettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var karoo: KarooSystemService
    private lateinit var repo: PlanRepository
    private lateinit var status: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = PlanRepository(this)
        karoo = KarooSystemService(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Workout Page"
            textSize = 22f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "intervals.icu API key (intervals.icu → Settings → Developer Settings). Used to show your workout's upcoming steps and graph."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, pad / 2, 0, pad / 2)
        })
        input = EditText(this).apply {
            hint = if (repo.apiKey != null) "Key saved (…${repo.apiKey!!.takeLast(4)}). Type to replace." else "API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
        }
        root.addView(input)
        root.addView(Button(this).apply {
            text = "Save and test"
            setOnClickListener {
                val k = input.text.toString().trim()
                if (k.isNotEmpty()) repo.apiKey = k
                input.setText("")
                test()
            }
        })
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            gravity = Gravity.START
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)
        setContentView(root)

        karoo.connect { connected -> if (connected) runOnUiThread { handleKeyExtra() } }
    }

    private fun handleKeyExtra() {
        val k = intent?.getStringExtra("api_key")?.trim()
        if (!k.isNullOrEmpty()) {
            repo.apiKey = k
            intent.removeExtra("api_key")
            test()
        } else {
            status.text = if (repo.apiKey == null) "No key saved yet." else "Key saved (…${repo.apiKey!!.takeLast(4)})."
        }
    }

    private fun test() {
        if (repo.apiKey == null) {
            status.text = "No key saved yet."
            return
        }
        status.text = "Checking intervals.icu…"
        scope.launch {
            val result = repo.fetchToday(karoo)
            status.text = when (result) {
                is PlanRepository.Result.Failed -> result.message
                is PlanRepository.Result.Ok -> {
                    val ws = WorkoutPlan.parseEvents(result.body)
                    if (ws.isEmpty()) {
                        "Key works. No ride workout planned today."
                    } else {
                        "Key works. Today: " + ws.joinToString("; ") { "${it.name} (${it.expanded.size} steps)" }
                    }
                }
            }
            LiveWorkout.instance?.refreshPlan()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        karoo.disconnect()
        super.onDestroy()
    }
}
