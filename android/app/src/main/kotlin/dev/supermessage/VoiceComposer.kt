package dev.supermessage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.supermessage.kit.SendResult
import dev.supermessage.kit.Session
import dev.supermessage.kit.VoiceRecording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.supermessage_ffi.voiceRecordingSpec

/**
 * What the composer needs to draw and drive voice recording. `null` in
 * [Composer] means no mic at all (a host with no recorder).
 *
 * [recording] is non-null while recording: its [elapsedMs] ticks, and [held]
 * says whether the finger is still down (then the mic stays the mic, and
 * releasing sends).
 */
class VoiceControls(
    val recording: Active?,
    val failure: String?,
    val onTap: () -> Unit,
    val onHoldStart: () -> Unit,
    val onHoldEnd: (cancelled: Boolean) -> Unit,
    val onDiscard: () -> Unit,
    val onSend: () -> Unit,
) {
    class Active(val elapsedMs: Long, val held: Boolean)
}

/** A new recorder for the platform: `MediaRecorder` and the audio focus. */
fun newVoiceRecording(context: Context, scope: kotlinx.coroutines.CoroutineScope): VoiceRecording {
    val app = context.applicationContext
    return VoiceRecording(
        recorder = MediaRecorderRecorder(app, voiceRecordingSpec()),
        cacheDir = app.cacheDir,
        scope = scope,
        route = AudioFocusRoute(app),
    )
}

private val sweptOnce = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * The composer's voice behaviour (`ComposerView.swift:199-216, 304-317`): the
 * tap and the hold, the permission at first use, and the send, which stages the
 * file, marks it as voice and sends with empty text ([Session.sendVoice]).
 *
 * The recording is stopped and thrown away when the room changes (the key),
 * when the composer leaves (sign-out ends the pane), and when the app stops
 * (a recording must not go on in the background). An incoming call is the
 * audio focus loss [VoiceRecording] handles.
 *
 * A delivered note plays the light send haptic. [microphone] is the permission ask; the default is the system dialog, asked
 * only when the permission is not already granted. A test passes its own.
 * [onRecordingStarts] runs before a recording begins: playback stops first, so
 * a note playing aloud is not recorded into the new one.
 */
@Composable
fun rememberVoiceControls(
    recording: VoiceRecording,
    session: Session,
    roomId: String,
    onRecordingStarts: () -> Unit,
    onSending: (Boolean) -> Unit,
    onResult: (SendResult) -> Unit,
    microphone: (suspend () -> Boolean)? = null,
    microphoneGranted: (Context) -> Boolean = {
        it.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    },
): VoiceControls {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phase by recording.phase.collectAsState()
    val failure by recording.failure.collectAsState()
    val startedAt = (phase as? VoiceRecording.Phase.Recording)?.startedAt

    remember(recording) { if (sweptOnce.compareAndSet(false, true)) recording.sweep(); 0 }

    // The permission: a launcher whose answer completes the pending ask.
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.complete(granted)
    }
    val ask: suspend () -> Boolean = microphone ?: {
        if (microphoneGranted(context)) true
        else {
            val answer = CompletableDeferred<Boolean>()
            pending[0] = answer
            launcher.launch(Manifest.permission.RECORD_AUDIO)
            answer.await()
        }
    }

    // Leaving the room, or the pane, or the app: throw it away.
    DisposableEffect(roomId, recording) { onDispose { recording.cancel() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { recording.cancel() }

    // The elapsed time, ticking while it records.
    var now by remember { mutableLongStateOf(recording.clock()) }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            now = recording.clock()
            delay(250)
        }
    }

    fun begin(held: Boolean) {
        onRecordingStarts()
        // Undispatched: a release right after the hold began must find the start already pending.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { recording.start(held, ask) }
    }

    fun sendIt() {
        val done = recording.finish() ?: return
        scope.launch {
            onSending(true)
            try {
                val result = session.sendVoice(done, roomId)
                // Light, and only for a send that reached the room (`ComposerView.swift:110-113`).
                if (result.delivered) haptics.play(Haptic.Light)
                onResult(result)
            } finally {
                onSending(false)
            }
        }
    }

    val active = (phase as? VoiceRecording.Phase.Recording)?.let {
        VoiceControls.Active(elapsedMs = (now - it.startedAt).coerceAtLeast(0), held = it.held)
    }
    return VoiceControls(
        recording = active,
        failure = failure,
        onTap = { begin(held = false) },
        onHoldStart = { begin(held = true) },
        onHoldEnd = { cancelled ->
            when {
                !recording.isRecording -> recording.abandonPendingStart()
                cancelled -> recording.cancel()
                else -> sendIt()
            }
        },
        onDiscard = { recording.cancel() },
        onSend = { sendIt() },
    )
}
