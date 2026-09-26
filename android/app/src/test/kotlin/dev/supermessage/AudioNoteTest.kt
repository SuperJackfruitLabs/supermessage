package dev.supermessage

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.supermessage.kit.AudioEngine
import dev.supermessage.kit.NotePlayback
import dev.supermessage.kit.VoicePlayback
import dev.supermessage.previews.PreviewFixtures
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.supermessage_core.PlayableAudio

/**
 * The voice-note row as a reader meets it: what the button and the time say
 * in each state, and that a tap on a row in the timeline reaches the one
 * player with that row's event id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioNoteTest {
    companion object {
        /** The elapsed clock is the core's `audioClockLabel`. */
        @BeforeClass
        @JvmStatic
        fun ensureHostCoreIsBuilt() = HostCore.ensureBuilt()
    }

    @get:Rule val compose = createComposeRule()

    private val voice = PreviewFixtures.voiceView()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dir: File = Files.createTempDirectory("voice").toFile()

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun atRestItOffersToPlayAndShowsTheLength() {
        compose.setContent {
            SupermessageTheme {
                AudioNote(audio = voice, isOwn = false, playback = NotePlayback.Idle, onToggle = {}, onSeek = {})
            }
        }
        compose.onNodeWithContentDescription("Play voice message").assertIsDisplayed()
        compose.onNodeWithTag("audio-time", useUnmergedTree = true).assertTextEquals("0:07")
    }

    @Test
    fun playingItOffersToPauseAndShowsElapsedTime() {
        compose.setContent {
            SupermessageTheme {
                AudioNote(
                    audio = voice, isOwn = true, playback = NotePlayback.Playing(3_400, 7_000),
                    onToggle = {}, onSeek = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        // Truncated, not rounded: 3.4 s in has played three seconds.
        compose.onNodeWithTag("audio-time", useUnmergedTree = true).assertTextEquals("0:03")
    }

    @Test
    fun aFailedNoteSaysSoAndOffersToTryAgain() {
        var taps = 0
        compose.setContent {
            SupermessageTheme {
                AudioNote(audio = voice, isOwn = false, playback = NotePlayback.Failed, onToggle = { taps++ }, onSeek = {})
            }
        }
        compose.onNodeWithTag("audio-time", useUnmergedTree = true).assertTextEquals("Can't play")
        compose.onNodeWithContentDescription("Try again").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun aTapInTheTimelineReachesThePlayerWithThatRowsEvent() {
        // The row's identity and its event id differ, as they do for any
        // message that began as a local echo: the file is addressed by the
        // event id, and a fixture where the two were equal could not tell.
        val row = PreviewFixtures.voiceOwn.let { it.copy(item = it.item.copy(id = "row-identity")) }
        val asked = mutableListOf<String>()
        val never = CompletableDeferred<PlayableAudio?>()
        val player = VoicePlayback(
            fetch = { id -> asked += id; never.await() },
            cacheDir = dir,
            engine = object : AudioEngine {
                override suspend fun open(file: File, onEnded: () -> Unit, onError: () -> Unit): Long? = null
                override fun play() {}
                override fun pause() {}
                override fun seekTo(positionMs: Long) {}
                override val positionMs: Long = 0
                override fun close() {}
            },
            scope = scope,
            io = Dispatchers.Unconfined,
        )
        compose.setContent {
            SupermessageTheme {
                CompositionLocalProvider(LocalVoicePlayback provides player) {
                    TimelineRow(row = row, now = Instant.EPOCH)
                }
            }
        }

        // Held still from here: the loading state is a spinner, and a
        // spinner under an auto-advancing clock never lets the test idle
        // (see PreviewScreenshotTest.NEVER_SETTLES).
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Play voice message").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()

            assertEquals(listOf("\$voice-own"), asked)
            assertEquals(NotePlayback.Loading, player.state.value.of("\$voice-own"))
            compose.onNodeWithContentDescription("Loading, tap to cancel").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
