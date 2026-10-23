package dev.supermessage.widgets

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.hasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasClickAction
import androidx.glance.testing.unit.assertHasNoClickAction
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import dev.supermessage.MainActivity
import dev.supermessage.kit.widgets.WidgetFixtures
import dev.supermessage.kit.widgets.WidgetFixtures.MINUTE
import dev.supermessage.kit.widgets.WidgetFixtures.NOW
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.supermessage_core.WidgetView

/**
 * The Needs you widget's states (`NeedsYouWidgetView.swift`), drawn from snapshots the **real**
 * core made, asserted as text, tags and click actions (`runGlanceAppWidgetUnitTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NeedsYouWidgetTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = WidgetFixtures.Memory()
    private val feed = WidgetFixtures.feed(store)

    private fun view(): WidgetView = feed.view(NOW)

    private fun show(
        size: DpSize = NeedsYouWidget.LARGE,
        fontScale: Float = 1f,
        check: GlanceAppWidgetUnitTest.() -> Unit,
    ) = runGlanceAppWidgetUnitTest {
        setContext(app)
        setAppWidgetSize(size)
        val shown = view()
        provideComposable { NeedsYouContent(shown, NOW, size, fontScale) }
        check()
    }

    private fun text(s: String) = hasTextEqualTo(s)

    private fun GlanceAppWidgetUnitTest.assertAbsent(s: String) {
        onNode(text(s)).assertDoesNotExist()
    }

    private val permissionAsk = "Run git push?"
    private val gateAsk = "Approve \"Ship v2\"?"

    @Test
    fun twoOwedDecisionsShowTheirQuestionsAndButtons() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        feed.apply(WidgetFixtures.gate(at = NOW - 2 * MINUTE))
        show {
            onNode(text(permissionAsk)).assertExists()
            onNode(text(gateAsk)).assertExists()
            onNode(text("Allow once")).assertExists()
            onNode(hasTestTag(answerTag("\$p", "Allow once"))).assertExists().assertHasRunCallbackClickAction<AnswerDecisionAction>(
                actionParametersOf(
                    AnswerDecisionAction.RoomKey to "!r:hs",
                    AnswerDecisionAction.EventKey to "\$p",
                    AnswerDecisionAction.OptionKey to "Allow once",
                ),
            )
            onNode(text("Approve")).assertExists()
            onNode(hasTestTag(answerTag("\$g", "approve"))).assertExists().assertHasRunCallbackClickAction<AnswerDecisionAction>(
                actionParametersOf(
                    AnswerDecisionAction.RoomKey to "!b:hs",
                    AnswerDecisionAction.EventKey to "\$g",
                    AnswerDecisionAction.OptionKey to "approve",
                ),
            )
            onAllNodes(text("Reject")).assertCountEquals(2)
            onNode(text("Needs you")).assertExists()
            onNode(text("2")).assertExists()
        }
    }

    @Test
    fun requestChangesHasNoButton() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        show {
            onNode(text("Approve")).assertExists()
            assertAbsent("Request changes")
            assertAbsent("request_changes")
        }
    }

    @Test
    fun aSentDecisionSaysSoInTheCoresWords() = runBlocking {
        feed.apply(WidgetFixtures.gate())
        feed.markAnswered("!b:hs", "\$g", "approve")
        show {
            onNode(text("Sent: Approve · waiting for the board")).assertExists()
            assertAbsent("Approve")
            assertAbsent("Reject")
            assertAbsent("Open to answer")
        }
    }

    @Test
    fun aDecisionWithNoInlineOptionsSaysOpenToAnswer() = runBlocking {
        feed.apply(WidgetFixtures.open())
        show {
            onNode(text("Which database should we use?")).assertExists()
            onNode(text("Open to answer")).assertExists()
        }
    }

    @Test
    fun theHeaderCountShowsOnlyWhenSomethingIsOwed() = runBlocking {
        feed.opened()
        show {
            onNode(text("Needs you")).assertExists()
            assertAbsent("0")
        }
        feed.apply(WidgetFixtures.permission())
        show { onNode(text("1")).assertExists() }
        feed.markAnswered("!r:hs", "\$p", "Allow once")
        show {
            onNode(text("Needs you")).assertExists()
            assertAbsent("1")
            assertAbsent("0")
        }
    }

    @Test
    fun nothingOwedShowsTheLineAndThePulse() = runBlocking {
        feed.apply(listOf(WidgetFixtures.row("!a:hs", "Atlas", NOW - 3 * 3_600_000, "hello")), emptyList(), NOW)
        val frame = view().frame!!
        assertEquals("Nothing needs you", frame.needsYouLine)
        show {
            onNode(text(frame.needsYouLine)).assertExists()
            onNode(text(frame.pulse)).assertExists()
        }
    }

    @Test
    fun fourDecisionsOnLargeShowThreeAndOneMoreInTheApp() = runBlocking {
        (1..4).forEach { i ->
            feed.apply(
                WidgetFixtures.permission(
                    event = "\$q$i", room = "!room$i:hs", question = "Question $i?", at = NOW - MINUTE + i * 1_000L,
                ),
            )
        }
        show {
            // Newest first: 4, 3, 2 are listed and 1 is counted.
            listOf(4, 3, 2).forEach { onNode(text("Question $it?")).assertExists() }
            assertAbsent("Question 1?")
            onNode(text("1 more in the app")).assertExists()
        }
    }

    @Test
    fun smallShowsTheFirstDecisionOnly() = runBlocking {
        feed.apply(WidgetFixtures.permission(event = "\$old", question = "Old?", at = NOW - 5 * MINUTE))
        feed.apply(WidgetFixtures.permission(event = "\$new", room = "!n:hs", question = "New?"))
        show(NeedsYouWidget.SMALL) {
            onNode(text("New?")).assertExists()
            assertAbsent("Old?")
            onNode(text("Allow once")).assertExists()
        }
    }

    private suspend fun threeDecisions() = (1..3).forEach { i ->
        feed.apply(WidgetFixtures.permission(event = "\$q$i", room = "!room$i:hs", question = "Question $i?", at = NOW - MINUTE + i * 1_000L))
    }

    /** The buckets the launcher is actually given are the only sizes tested here. */
    @Test
    fun theBucketsAreTheSizesTheLauncherPicksFrom() {
        assertEquals(
            listOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 180.dp), DpSize(250.dp, 250.dp)),
            listOf(NeedsYouWidget.SMALL, NeedsYouWidget.WIDE, NeedsYouWidget.MEDIUM, NeedsYouWidget.LARGE),
        )
        assertEquals(listOf(1, 1, 2, 3), listOf(NeedsYouWidget.SMALL, NeedsYouWidget.WIDE, NeedsYouWidget.MEDIUM, NeedsYouWidget.LARGE).map(::listLimit))
    }

    @Test
    fun theFitStepsDownFromButtonsToTheQuestionAloneAsTheTextGrows() {
        fun fit(bucket: DpSize, fontScale: Float) =
            cardsThatFit(listLimit(bucket), 3, bucket.height, fontScale, compact = bucket.width < NeedsYouWidget.WIDE.width)
        assertEquals(CardFit(1, false, CardMode.FULL), fit(NeedsYouWidget.SMALL, 1f))
        assertEquals(CardFit(1, true, CardMode.BARE), fit(NeedsYouWidget.SMALL, 1.5f))
        assertEquals(CardFit(1, false, CardMode.FULL), fit(NeedsYouWidget.WIDE, 1f))
        assertEquals(CardFit(1, true, CardMode.TEXT), fit(NeedsYouWidget.LARGE, 2f))
        assertEquals(CardFit(2, false, CardMode.BARE), fit(NeedsYouWidget.MEDIUM, 2f))
    }

    @Test
    fun mediumListsTwoAndTheRestAreCounted() = runBlocking {
        threeDecisions()
        // 4x3 lands in MEDIUM: two cards with their buttons and "1 more", as iOS's medium.
        assertEquals(CardFit(2, true, CardMode.FULL), cardsThatFit(listLimit(NeedsYouWidget.MEDIUM), 3, NeedsYouWidget.MEDIUM.height, 1f))
        show(NeedsYouWidget.MEDIUM) {
            onNode(text("Question 3?")).assertExists()
            onNode(text("Question 2?")).assertExists()
            assertAbsent("Question 1?")
            onNode(text("1 more in the app")).assertExists()
            onAllNodes(text("Allow once")).assertCountEquals(2)
        }
    }

    @Test
    fun wideShowsOneCardAndACardIsWorthMoreThanTheMoreLine() = runBlocking {
        threeDecisions()
        // 4x2 is 110dp: one card; the "N more" line would crop it, so it is dropped.
        show(NeedsYouWidget.WIDE) {
            onNode(text("Question 3?")).assertExists()
            assertAbsent("Question 2?")
            assertAbsent("2 more in the app")
            onNode(text("Allow once")).assertExists()
        }
    }

    @Test
    fun aCardSaysHowLongAgoTheWayIosDoes() = runBlocking {
        feed.apply(WidgetFixtures.permission(at = NOW - 4 * MINUTE))
        show { onNode(text("4m")).assertExists() }
        feed.apply(WidgetFixtures.permission(event = "\$old", room = "!old:hs", question = "Old?", at = NOW - 130 * MINUTE))
        show {
            onNode(text("2h")).assertExists()
            onNode(text("4m")).assertExists()
        }
    }

    @Test
    fun signedOutAndWaitingSayWhatToDo() = runBlocking {
        show {
            onNode(text("supermessage")).assertExists()
            onNode(text("Open the app once to start.")).assertExists()
        }
        feed.signedOut()
        show {
            onNode(text("supermessage")).assertExists()
            onNode(text("Sign in to see what needs you.")).assertExists()
            assertAbsent("Needs you")
        }
    }

    @Test
    fun theTextOpensTheDecision() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        val link = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("supermessage://open?room=%21r%3Ahs&event=%24p"),
            app,
            MainActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("dev.supermessage.extra.ROOM", "!r:hs")
            .putExtra("dev.supermessage.extra.EVENT", "\$p")
        show {
            onNode(hasStartActivityClickAction(link)).assertExists().assertHasClickAction()
            // The buttons answer in place: they do not start an Activity.
            onNode(hasTestTag(answerTag("\$p", "Allow once"))).assertHasClickAction()
        }
        assertEquals(
            ComponentName(app, MainActivity::class.java),
            AnswerFromWidget.decisionIntent(app, "!r:hs", "\$p").component,
        )
    }

    @Test
    fun onlyAnOwedDecisionIsAmber() = runBlocking {
        feed.apply(WidgetFixtures.permission())
        feed.apply(WidgetFixtures.gate(at = NOW - 2 * MINUTE))
        feed.markAnswered("!b:hs", "\$g", "approve")
        show {
            onNode(hasTestTag("decision-owed:\$p")).assertExists()
            onNode(hasTestTag("decision-sent:\$g")).assertExists()
            onAllNodes(hasTestTag("decision-owed:\$g")).assertCountEquals(0)
        }
        // The palette: amber is the owed card's, the muted rank is the sent one's.
        assertEquals(CardPalette(WidgetColors.signal, WidgetColors.signalSoft), cardPalette(owed = true))
        assertEquals(CardPalette(WidgetColors.border, WidgetColors.surfaceSunken), cardPalette(owed = false))
    }

    @Test
    fun atFontScaleTwoLargeKeepsTheHeaderAndOneCardWithoutButtons() = runBlocking {
        threeDecisions()
        show(NeedsYouWidget.LARGE, fontScale = 2f) {
            onNode(text("Needs you")).assertExists()
            onNode(text("3")).assertExists()
            onNode(text("Question 3?")).assertExists()
            assertAbsent("Question 2?")
            onNode(text("2 more in the app")).assertExists()
            // Not even one card with its buttons fits: the question, and a tap to open it.
            onNode(text("Open to answer")).assertExists()
            assertAbsent("Allow once")
        }
    }

    @Test
    fun atFontScaleTwoMediumAndSmallDropTheButtonsRatherThanCropThem() = runBlocking {
        threeDecisions()
        // Twice the text in 180dp cannot hold an agent line and buttons: two questions alone.
        show(NeedsYouWidget.MEDIUM, fontScale = 2f) {
            onNode(text("Needs you")).assertExists()
            onNode(text("Question 3?")).assertExists()
            onNode(text("Question 2?")).assertExists()
            assertAbsent("Hermes")
            assertAbsent("Allow once")
        }
        // In 110dp not even two: the question alone.
        listOf(NeedsYouWidget.WIDE, NeedsYouWidget.SMALL).forEach { bucket ->
            show(bucket, fontScale = 2f) {
                onNode(text("Needs you")).assertExists()
                onNode(text("Question 3?")).assertExists()
                assertAbsent("Hermes")
                assertAbsent("Allow once")
                assertAbsent("Question 2?")
            }
        }
        // At the normal size the same buckets keep them.
        show(NeedsYouWidget.SMALL) { onNode(text("Allow once")).assertExists() }
    }
}
