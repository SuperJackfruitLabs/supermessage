package dev.supermessage

import android.content.Intent

/** The release build has no debug launch switch (the debug source set has the real one). */
@Suppress("UNUSED_PARAMETER")
internal suspend fun applyDebugLaunch(intent: Intent?, firstRun: FirstRunPreferences) = Unit
