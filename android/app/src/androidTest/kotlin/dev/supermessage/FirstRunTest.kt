package dev.supermessage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import dev.supermessage.kit.CoreClient
import dev.supermessage.kit.FirstRunDemo
import dev.supermessage.kit.Session
import dev.supermessage.kit.StubCore
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_core.SpaceSummary
import uniffi.supermessage_ffi.CoreInterface
import uniffi.supermessage_ffi.EventSink
import uniffi.supermessage_ffi.RoomsSnapshot
import uniffi.supermessage_ffi.firstRunScript

/**
 * The welcome and the first-run demo (O1; iOS `WelcomeView.swift`, `FirstRunDemoView.swift`).
 */
class FirstRunTest {
    @get:Rule val compose = createComposeRule()

    private val script = firstRunScript()

    // ── Doubles ────────────────────────────────────────────────────────────────────────────

    private class RecordingHaptics : HapticFeedback {
        val played = Collections.synchronizedList(mutableListOf<HapticFeedbackType>())
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    /** A signed-out or restorable account, with a recovery key if asked. */
    private class Core(val restores: Boolean, val key: String?, val keyGate: java.util.concurrent.CountDownLatch? = null) : StubCore() {
        override fun restoreSession(sink: EventSink): Boolean = restores
        override fun login(homeserver: String, username: String, password: String, sink: EventSink) {}
        override fun logout() {}
        override fun roomsSnapshot(): RoomsSnapshot = RoomsSnapshot(seq = 0uL, rooms = emptyList())
        override fun spacesList(): List<SpaceSummary> = emptyList()
        override fun account(): AccountDto = AccountDto(userId = "@me:example.org", homeserver = "https://example.org")
        override fun ignoredUsers(): List<uniffi.supermessage_core.RoomMemberDto> = emptyList()
        override fun knownPeople(): List<uniffi.supermessage_core.PersonDto> = emptyList()
        override fun ensureRecovery(): String? {
            keyGate?.await(20, java.util.concurrent.TimeUnit.SECONDS)
            return key
        }
        override fun recoveryState(): String = "enabled"
        override fun roomAvatar(roomId: String): String? = null
        override fun setHiddenEvents(eventIds: List<String>) {}
        override fun markRoomRead(roomId: String) {}
        override fun setTyping(roomId: String, typing: Boolean) {}
        override fun turn(roomId: String): uniffi.supermessage_core.TurnSnapshot? = null
        override fun timelineSubscribe(roomId: String, sink: EventSink) {}
        override fun timelineResync() = uniffi.supermessage_ffi.TimelineSnapshot(roomId = "", seq = 0uL, items = emptyList())
        override fun timelinePaginateBack(roomId: String, count: UShort): Boolean = true
    }

    /** Every call that reaches the core, by name: the proof of what the demo did not do. */
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun recording(core: CoreInterface): CoreInterface =
        Proxy.newProxyInstance(CoreInterface::class.java.classLoader, arrayOf(CoreInterface::class.java)) { _, method, args ->
            if (method.declaringClass != Any::class.java) calls += method.name
            try {
                method.invoke(core, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as CoreInterface

    private fun sessionOf(core: Core) = Session(
        client = CoreClient(core = recording(core), dispatcher = Dispatchers.IO, resumeOn = Dispatchers.Main.immediate),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun roster() = RosterPreferences(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) {
            File.createTempFile("first-run-roster", ".preferences_pb")
        },
    )

    private fun firstRunPrefs() = FirstRunPreferences(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.Unconfined)) {
            File.createTempFile("first-run", ".preferences_pb")
        },
    )

    private val haptics = RecordingHaptics()

    private var epoch by mutableIntStateOf(0)

    private fun showApp(session: Session, firstRun: FirstRunPreferences, fontScale: Float = 1f) {
        val prefs = roster()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalHapticFeedback provides haptics,
                LocalReduceMotion provides false,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                Box(Modifier.requiredWidth(411.dp).fillMaxHeight().testTag("test-shell")) {
                    key(epoch) { AppRoot(session = session, prefs = prefs, firstRun = firstRun) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun present(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String, millis: Long = 15_000) = compose.waitUntil(millis) { present(tag) }

    private fun signInThroughTheForm() {
        waitFor("welcome")
        compose.onNodeWithTag("welcome-sign-in").performClick()
        waitFor("username")
        compose.onNodeWithTag("username").performTextInput("sam")
        compose.onNodeWithTag("password").performTextInput("hunter2")
        // By action, not by touch: at the short height the keyboard covers the form's button, which is
        // the form's own layout and not what these tests are about.
        compose.onNodeWithTag("sign-in").performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun pressBackIn(tag: String) {
        val view = (compose.onNodeWithTag(tag).fetchSemanticsNode().root as ViewRootForTest).view
        val dispatcher = checkNotNull(view.findViewTreeOnBackPressedDispatcherOwner()) { "no back dispatcher" }.onBackPressedDispatcher
        compose.runOnUiThread { dispatcher.onBackPressed() }
    }

    // ── The welcome ────────────────────────────────────────────────────────────────────────

    @Test
    fun aFirstLaunchSignedOutWelcomes() {
        val firstRun = firstRunPrefs()
        showApp(sessionOf(Core(restores = false, key = null)), firstRun)
        waitFor("welcome")
        compose.onNodeWithTag("welcome").assertIsDisplayed()
        compose.onNodeWithText("supermessage").assertIsDisplayed()
        compose.onNodeWithText("Chat with your team and your agents — and approve their work without leaving the conversation.")
            .assertIsDisplayed()
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithTag("homeserver").assertDoesNotExist()

        compose.onNodeWithTag("welcome-sign-in").performClick()
        waitFor("homeserver")
        compose.onNodeWithTag("welcome").assertDoesNotExist()
        assertTrue(runBlocking { firstRun.welcomeSeen.first() })

        // Remembered per install: a fresh composition over the same preferences is the form.
        epoch += 1
        compose.waitForIdle()
        waitFor("homeserver")
        compose.onNodeWithTag("welcome").assertDoesNotExist()
    }

    @Test
    fun theMarkScalesButNeverFades() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides false) { WelcomeScreen(onSignIn = {}) }
        }
        compose.mainClock.advanceTimeByFrame()
        val first = compose.onNodeWithTag("welcome-mark").fetchSemanticsNode()
        assertEquals("it is still arriving at the first frame", true, first.config.getOrNull(Arriving))
        val layer = graphicsLayerOf(first)
        assertEquals("opaque from the first frame", 1f, layer.alpha, 0f)
        assertTrue("and smaller than it will be: ${layer.scale}", layer.scale < 1f)

        compose.mainClock.advanceTimeBy(2_000)
        val last = compose.onNodeWithTag("welcome-mark").fetchSemanticsNode()
        assertEquals(false, last.config.getOrNull(Arriving))
        assertEquals(1f, graphicsLayerOf(last).alpha, 0f)
        assertEquals(1f, graphicsLayerOf(last).scale, 0.001f)
    }

    @Test
    fun theMarkDoesNotMoveUnderReduceMotion() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) { WelcomeScreen(onSignIn = {}) }
        }
        compose.mainClock.advanceTimeByFrame()
        val node = compose.onNodeWithTag("welcome-mark").fetchSemanticsNode()
        assertEquals(false, node.config.getOrNull(Arriving))
        assertEquals(1f, graphicsLayerOf(node).scale, 0f)
    }

    private class Layer(val alpha: Float, val scale: Float)

    /**
     * What the node's `graphicsLayer { }` block sets, read by running the block against a recording
     * scope: the one honest way to ask a layer for its alpha from a test.
     */
    private fun graphicsLayerOf(node: SemanticsNode): Layer {
        val element = node.layoutInfo.getModifierInfo().map { it.modifier }
            .first { it.javaClass.name.contains("GraphicsLayer") }
        val block = generateSequence<Class<*>>(element.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { kotlin.Function1::class.java.isAssignableFrom(it.type) }
            .also { it.isAccessible = true }
            .get(element)
        val set = mutableMapOf<String, Any?>()
        val scopeClass = Class.forName("androidx.compose.ui.graphics.GraphicsLayerScope")
        val scope = Proxy.newProxyInstance(scopeClass.classLoader, arrayOf(scopeClass)) { _, m, args ->
            when {
                m.name.startsWith("set") -> { set[m.name.removePrefix("set")] = args!![0]; null }
                m.name.startsWith("get") -> set[m.name.removePrefix("get")] ?: when (m.returnType) {
                    java.lang.Float.TYPE -> if (m.name in listOf("getAlpha", "getScaleX", "getScaleY", "getDensity", "getFontScale")) 1f else 0f
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
                else -> null
            }
        }
        @Suppress("UNCHECKED_CAST")
        (block as (Any) -> Unit)(scope)
        val alpha = (set["Alpha"] as Float?) ?: 1f
        val scaleX = (set["ScaleX"] as Float?) ?: 1f
        return Layer(alpha, scaleX)
    }

    // ── When the demo is queued, and when it shows ─────────────────────────────────────────

    @Test
    fun anInteractiveSignInQueuesTheDemo() {
        showApp(sessionOf(Core(restores = false, key = null)), firstRunPrefs())
        signInThroughTheForm()
        waitFor("first-run")
        compose.onNodeWithTag("first-run-banner").assertIsDisplayed()
        compose.onNodeWithText("A sample conversation — nothing here is sent anywhere.").assertIsDisplayed()
    }

    @Test
    fun aRestoredSessionNeverQueuesTheDemo() {
        showApp(sessionOf(Core(restores = true, key = null)), firstRunPrefs())
        waitFor("destination-chats")
        // Longer than the demo takes to appear, were it going to.
        Thread.sleep(1_500)
        compose.waitForIdle()
        assertFalse(present("first-run"))
    }

    @Test
    fun theDemoWaitsForTheRecoveryKey() {
        showApp(sessionOf(Core(restores = false, key = "EsTd KEY1 KEY2")), firstRunPrefs())
        signInThroughTheForm()
        waitFor("new-recovery-key")
        Thread.sleep(500)
        compose.waitForIdle()
        assertFalse("the demo stays out while the key is up", present("first-run"))
        compose.onNodeWithTag("new-recovery-key-done").performClick()
        waitFor("first-run")
    }

    /** Before the core has said whether a key is coming, the demo cannot know to wait for it. */
    @Test
    fun theDemoWaitsUntilTheCoreHasSaidWhetherThereIsAKey() {
        val gate = java.util.concurrent.CountDownLatch(1)
        showApp(sessionOf(Core(restores = false, key = null, keyGate = gate)), firstRunPrefs())
        signInThroughTheForm()
        waitFor("destination-chats")
        Thread.sleep(700)
        compose.waitForIdle()
        assertFalse("the answer about the key is still out", present("first-run"))
        gate.countDown()
        waitFor("first-run")
    }

    // ── The demo itself ────────────────────────────────────────────────────────────────────

    @Test
    fun theDemoIsPacedAndStopsAtTheCard() {
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        compose.setContent {
            FirstRunDemoScreen(onFinish = {}, delay = { ms ->
                delays += ms
                gates[delays.size - 1].await()
            })
        }
        compose.waitForIdle()
        compose.onNodeWithTag("first-run-typing").assertIsDisplayed()
        compose.onNode(hasContentDescription("Atlas is typing")).assertIsDisplayed()
        assertFalse(present("first-run-message"))
        assertEquals(listOf(1100L), delays.toList())

        gates[0].complete(Unit)
        compose.waitUntil(5_000) { present("first-run-message") }
        compose.onNodeWithText("Hi — I'm Atlas, a sample agent. I'd like to tidy the release notes before today's build.")
            .assertIsDisplayed()
        compose.onNodeWithText("Agent").assertIsDisplayed()
        assertFalse(present("first-run-typing"))
        assertEquals(listOf(1100L, 900L), delays.toList())

        gates[1].complete(Unit)
        waitFor("first-run-card")
        compose.onNodeWithText("Approve").assertIsDisplayed()
        assertFalse(present("first-run-approved"))
        compose.waitForIdle()
        assertEquals("the pace stops at the card", listOf(1100L, 900L), delays.toList())
    }

    @Test
    fun theReduceMotionPaceIsTheSame() {
        val delays = Collections.synchronizedList(mutableListOf<Long>())
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                FirstRunDemoScreen(onFinish = {}, delay = { ms -> delays += ms })
            }
        }
        waitFor("first-run-card")
        assertEquals(listOf(1100L, 900L), delays.toList())
    }

    private fun showDemoAtTheCard(onFinish: () -> Unit = {}, fontScale: Float = 1f, demo: FirstRunDemo = FirstRunDemo()) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalHapticFeedback provides haptics,
                LocalReduceMotion provides false,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                FirstRunDemoScreen(onFinish = onFinish, demo = demo, delay = { })
            }
        }
        waitFor("first-run-card")
    }

    @Test
    fun approvingFinishesTheDemo() {
        val firstRun = firstRunPrefs()
        runBlocking { firstRun.setDemoPending(true) }
        var finished = 0
        showDemoAtTheCard(onFinish = { finished++; runBlocking { firstRun.finishDemo() } })
        compose.onNodeWithTag("first-run-skip").assertIsDisplayed()

        compose.onNodeWithText("Approve").performClick()
        waitFor("first-run-approved")
        compose.onNodeWithText("That's it — approvals from chat.").assertIsDisplayed()
        compose.onNodeWithText("When an agent needs you, it asks in its room, and the Needs you tab keeps the list.").assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed()
        compose.onNodeWithTag("first-run-skip").assertDoesNotExist()
        assertEquals(1, haptics.played.count { it == HapticFeedbackType.Confirm })

        compose.onNodeWithTag("first-run-finish").performClick()
        compose.waitForIdle()
        assertEquals(1, finished)
        assertTrue(runBlocking { firstRun.demoSeen.first() })
        assertFalse(runBlocking { firstRun.demoPending.first() })
    }

    @Test
    fun skipIsThereUntilApproved() {
        var finished = 0
        val demo = FirstRunDemo()
        showDemoAtTheCard(onFinish = { finished++ }, demo = demo)
        compose.onNodeWithText("Skip").assertIsDisplayed()
        compose.onNodeWithTag("first-run-skip").performClick()
        assertEquals(1, finished)
    }

    @Test
    fun skipIsThereWhileAtlasIsTyping() {
        var finished = 0
        compose.setContent { FirstRunDemoScreen(onFinish = { finished++ }, delay = { kotlinx.coroutines.awaitCancellation() }) }
        waitFor("first-run-typing")
        compose.onNodeWithTag("first-run-skip").assertIsDisplayed().performClick()
        assertEquals(1, finished)
    }

    @Test
    fun theSystemBackIsSkip() {
        var finished = 0
        showDemoAtTheCard(onFinish = { finished++ })
        pressBackIn("first-run")
        compose.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun finishingInTheAppDoesNotComeBack() {
        val firstRun = firstRunPrefs()
        showApp(sessionOf(Core(restores = false, key = null)), firstRun)
        signInThroughTheForm()
        waitFor("first-run-card", 20_000)
        compose.onNodeWithText("Approve").performClick()
        waitFor("first-run-finish")
        compose.onNodeWithTag("first-run-finish").performClick()
        compose.waitUntil(5_000) { !present("first-run") }
        assertTrue(runBlocking { firstRun.demoSeen.first() })
        assertFalse(runBlocking { firstRun.demoPending.first() })
        epoch += 1
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertFalse(present("first-run"))
    }

    /** RF5: nothing in the demo reaches the core, from the first word to "Get started". */
    @Test
    fun theDemoMakesNoCoreCallsFromStartToFinish() {
        showApp(sessionOf(Core(restores = false, key = null)), firstRunPrefs())
        signInThroughTheForm()
        waitFor("first-run")
        compose.waitForIdle()
        calls.clear()
        waitFor("first-run-card", 20_000)
        compose.onNodeWithText("Approve").performClick()
        waitFor("first-run-finish")
        compose.onNodeWithTag("first-run-finish").performClick()
        compose.waitUntil(5_000) { !present("first-run") }
        assertEquals("the demo called the core", emptyList<String>(), calls.toList())
    }

    @Test
    fun theDemoFitsAtFontScaleTwo() {
        showDemoAtTheCard(fontScale = 2f)
        compose.onNodeWithTag("first-run-message").assertIsDisplayed()
        compose.onNodeWithText("Approve").performScrollTo().performClick()
        waitFor("first-run-finish")
        compose.onNodeWithText("Get started").performScrollTo().assertIsDisplayed()
        // The greeting is whole: laid out in full, with no line cut or ellipsized, and its node is
        // as tall as that layout.
        val greeting = compose.onNodeWithText(script.greeting).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        greeting.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(layouts)
        val layout = layouts.single()
        assertTrue("the greeting wraps at this scale", layout.lineCount > 1)
        assertFalse("the greeting overflows its box", layout.didOverflowHeight)
        assertFalse("the greeting is ellipsized", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        assertTrue(
            "the greeting's node (${greeting.size.height}px) is shorter than its text (${layout.size.height}px)",
            greeting.size.height >= layout.size.height,
        )
    }

    @Test
    fun theWelcomeFitsAtTheShortHeight() {
        compose.setContent { Box(Modifier.requiredWidth(411.dp).fillMaxHeight()) { WelcomeScreen(onSignIn = {}) } }
        val tagline = compose.onNodeWithTag("welcome-tagline").performScrollTo().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("welcome-sign-in").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Sign in is below the tagline, not over it: $tagline $button", button.top >= tagline.bottom)
    }
}
