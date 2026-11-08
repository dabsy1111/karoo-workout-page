# Workout Page — Karoo extension (v3)

One graphical data field that **runs today's intervals.icu workout itself**, laid out like Garmin's workout screen. Give it a whole page and **don't load a workout on the Karoo**.

- **Arc gauge**: grey track, the current step's target range in its zone colour, and a needle at your 3s power
- **3s power** in the middle, in its zone colour, with the step's **AVG** and **MAX** either side and the target range under it
- **STEPS 3/7** (before the start: the workout's name)
- **Workout graph**: zoomed on the current step (80% of the width) with a slice of the previous and next step either side. Blocks show targets in zone colour, FTP is the dashed line, the power you've ridden is red, the black line is now
- **Current step**: time left in large figures across the full width (the graph's "now" line shows how far through you are), then the next step's length and target
- **Controls**: back, start / pause, next

## Riding it

1. Start the ride as normal (no Karoo workout). The field shows today's workout, its total time and the first step.
2. Tap the green **▶** when you're ready. Riding before that doesn't count.
3. Each step change beeps and marks a lap, so intervals.icu splits the ride at every step. (No pop-up alert: the field already shows the new target.) The last 3 seconds of a timed step beep.
4. **⏸** pauses the workout timer only (the ride keeps recording). The timer also stops whenever the Karoo pauses the ride (auto-pause, or you stop it).
5. **⏭** ends the step now. **⏮** goes to the previous step, or back to the start of this one if you're more than 10 seconds in.
6. "Press lap" steps (e.g. 15 min recovery before a rep) wait for you. They count down their planned time; when it's up the Karoo beeps, an alert says **"Press lap to start next interval"** with what's next, and the same reminder (in red) replaces the big time-left figure until you press the Karoo's **lap button** (or tap **⏭**).
7. If intervals.icu has more than one ride workout today, tap the name (shown as "1/2 · name") to switch before starting.

When the last step ends the extension does nothing more: no lap, beep or alert, and the ride carries on as normal. If the workout has steps marked `intensity=interval` in intervals.icu (e.g. `- 18m 90% intensity=interval`), the field turns into a summary of just those: one row each with time, target, average (in its zone colour), NP and max, plus an "All" row for all of them together. Otherwise it simply shows "Done". The marking is read from the step data, or from the workout text if the steps don't carry it. Back still reopens the last step.

Text uses the Karoo's own condensed font (`ibm-plex-sans-condensed`, as Karoo's fields do), and sizes are measured in it as they're drawn, so numbers shrink to fit rather than get cut off.

Ramps move the target smoothly from start to end watts (the arc shows ±3% around it). If the extension restarts mid-ride, it carries on from the step and time it saved (every 10 s). Saving the ride resets everything for the next one.

**Not included:** ERG control of a smart trainer, and target power in the ride file. Karoo's own workout player still works if you need ERG; just don't run both at once.

## Battery

Nothing runs on a timer until you tap Start. Before that (and once the workout is finished) the extension only reacts to events: the ride starting or ending, profile changes, the lap button, your taps, and the once-a-day intervals.icu download. It doesn't listen to the power meter, so 3s power shows "--" until you start. While a workout is running or paused it updates once a second as before. The arc and graph images are drawn at half size (the Karoo scales them up), the arc is only redrawn when power or the target changes, and the graph at most every 5 seconds or when the step changes.

## intervals.icu (for the graph and upcoming steps)

The workout comes from your intervals.icu calendar:

1. Get your API key: intervals.icu → Settings → Developer Settings.
2. Either open the **Workout Page** app on the Karoo and type it in, or send it over USB:
   `adb shell am start -n com.dan.workoutpage/.SettingsActivity --es api_key YOURKEY`
3. The screen confirms it with today's workout, e.g. "Key works. Today: Threshold 2x20min (5 steps)".

The extension downloads today's workouts when it starts (through the Karoo's own connection, retrying every minute if offline) and keeps a copy for the day, so it only needs signal once, e.g. on home Wi-Fi before you leave. Requests go only to intervals.icu. Targets are in watts as intervals.icu works them out from your intervals.icu FTP.

## Zone colours

Z1 teal `#4fbca7`, Z2 green `#4fbc4f`, Z3 gold `#c99400`, Z4 orange `#e06400` (both darker than intervals.icu's for sunlight), Z5 pink-red `#e85280`, Z6 purple `#9572dc`, Z7 red `#d32f2f` (intervals.icu uses grey for Z7).

Zone limits come from the Karoo rider profile: its own 7 power zones if it has them, otherwise intervals.icu's default 55 / 75 / 90 / 105 / 120 / 150 % of the Karoo FTP. With no FTP, numbers stay black.

## Things to check on the first ride

- That taps register while riding. Another Karoo 3 extension uses the same method, but Karoo hasn't documented it.
- That beeps and step alerts show up as expected.

## Installing

Latest signed build, always: https://github.com/dabsy1111/karoo-workout-page/releases/latest/download/WorkoutPage.apk

`adb install -r WorkoutPage.apk` with the Karoo on USB and USB debugging on. After that the Karoo offers updates itself: the app points it at this repo's latest `manifest.json`.

## Releases

Every push that changes the app (or **Actions → Build signed APK → Run workflow**) runs `.github/workflows/build-apk.yml`: it restores the signing key from the `WORKOUTPAGE_KEYSTORE` secret, runs the unit tests, builds the release APK, checks it's signed with the shared key (`CN=Workout Page`), and publishes release `v<versionName>` with `WorkoutPage-<version>.apk`, `WorkoutPage.apk`, `manifest.json` and `icon.png`.

**Bump `versionCode` and `versionName` in `app/build.gradle.kts` on every change**: the Karoo only offers an update when `versionCode` goes up, and the release tag comes from `versionName`. Put a line for the update screen in `app/release-notes.txt`.

The key (`keystore/workoutpage.jks`) is never committed. Builds sign debug and release with it when the file is there and fall back to the local debug key when it isn't (such a build can't update a copy installed from the releases).

## Building locally

Open this folder in Android Studio, let Gradle sync, then **Build → Build APK(s)**. karoo-ext 1.1.9 (Apache 2.0, SRAM LLC) is included as source.

Tests: `./gradlew :app:testDebugUnitTest`. Covers parsing intervals.icu workouts, the workout engine (timing, pause, skip/back, lap steps, ramps, finish), the power tracker, graph spacing, the interval summary, and `RenderFitTest`, which lays out the real field with Robolectric at 480–744 px tall in every state and fails if any text is clipped.

## Files

- `WorkoutPageExtension.kt`: the extension service; starts `LiveWorkout`
- `WorkoutEngine.kt`: runs the workout (steps, timing, pause, skip, back, lap-press steps, ramps)
- `LiveWorkout.kt`: while a workout runs, once a second: power in, engine and tracker forward, beeps / alerts / laps out, progress saved. Idle otherwise
- `ControlReceiver.kt`: receives taps on the buttons (not exported)
- `WorkoutTracker.kt`: per-step power record, AVG/MAX, survives skips and rewinds
- `WorkoutPlan.kt`: parses intervals.icu workouts into steps in watts
- `PlanRepository.kt`, `SettingsActivity.kt`: API key, download and cache
- `PageRenderer.kt`: draws the field (arc and graph as images, text as views) and the post-workout summary
- `IntervalSummary.kt`: per-interval time, average, NP and max for the summary
- `PageState.kt`: what's on screen, zones, colours, formatting
- `res/layout/workout_page.xml`: rows of 40 / 7 / 16 / 27 / 10 % of the field height; the 27 % step block is 70 % current step, 30 % next step
