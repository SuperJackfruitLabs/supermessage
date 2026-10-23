package dev.supermessage

import android.content.Intent

/** `adb shell am start -n dev.supermessage/.MainActivity --ez dev.supermessage.debug.firstRun true` */
internal const val DEBUG_FIRST_RUN_EXTRA = "dev.supermessage.debug.firstRun"

/**
 * The debug build's one launch switch: the first-run demo, queued afresh, so it can be shown on a device
 * that is already signed in without signing in (iOS's `-openAccount` precedent, `RootView.swift:103-112`).
 * The release build has no such path: its [applyDebugLaunch] is empty.
 */
internal suspend fun applyDebugLaunch(intent: Intent?, firstRun: FirstRunPreferences) {
    if (intent?.getBooleanExtra(DEBUG_FIRST_RUN_EXTRA, false) == true) firstRun.queueDemoForDebug()
}
