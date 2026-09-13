package dev.supermessage

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.supermessage.previews.CardAnswered
import dev.supermessage.previews.CardHostileEventType
import dev.supermessage.previews.CardLongValue
import dev.supermessage.previews.CardPending
import dev.supermessage.previews.RichTextEveryBlock
import dev.supermessage.previews.RoomRowStates
import dev.supermessage.previews.RoomRowStatesDark
import dev.supermessage.previews.RosterFurnished
import dev.supermessage.previews.SendStates
import dev.supermessage.previews.TimelineVocabulary
import dev.supermessage.previews.UnbreakableBody
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Writes the rule-carrying previews to PNGs, on the emulator CI already boots.
 *
 * ## Why this rather than Roborazzi or AGP's screenshot plugin
 *
 * Both were the obvious answer and both were declined **on this project's one
 * real constraint: there is no Android SDK on the machine this was written
 * on**, so CI is the only compiler and every unknown costs a sixteen-minute
 * round trip. Roborazzi means a new Gradle plugin, Robolectric, and a
 * Robolectric/`compileSdk 36` pairing, against **AGP 9.3.1** — three unknowns,
 * and Roborazzi's release notes say nothing either way about AGP 9. AGP's own
 * `com.android.compose.screenshot` plugin is version-aligned by construction
 * but wants a `screenshotTest` source set, which collides with the `src/debug`
 * decision that let `compose-ui-tooling-preview` leave release scope.
 *
 * This adds **no plugin and no dependency**. It uses the instrumented Compose
 * test infrastructure that already runs here — twenty-four files of it — and
 * `captureToImage`, which is part of `compose-ui-test-junit4`.
 *
 * ## What it does not do
 *
 * It does not render all 48 previews. It renders the eleven that carry a rule
 * from `docs/design-language.md`, which are the ones listed in
 * `docs/platform-parity.md` §6 as worth looking at first. That is a curated
 * set by intent rather than a mirror that has fallen behind: a preview added
 * tomorrow will not appear here, and should only be added if it carries a
 * rule.
 *
 * The iOS half takes the opposite approach — it enumerates the preview
 * registry and renders everything — because a package exists there that can.
 * Nothing equivalent is being added here to chase symmetry.
 *
 * ## Where the images go
 *
 * The app's external files directory, which CI pulls with `adb` and uploads
 * as an artifact. Not committed and not compared: like the iOS side, this is
 * a viewer rather than a regression gate. Several frames read the wall clock
 * through relative-time formatting, so a committed baseline would diff
 * against itself.
 */
class PreviewCaptureTest {
    @get:Rule val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        compose.waitForIdle()

        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null),
            "preview-captures",
        )
        dir.mkdirs()
        File(dir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /**
     * The amber rule, and the only frame where "exactly one row is amber" is
     * checkable by eye. A second amber row here is a defect per
     * `docs/design-language.md` §2 rather than a taste disagreement.
     */
    @Test
    fun rosterRows() = capture("roster-rows") { RoomRowStates() }

    /**
     * The same rows in dark, where `content-faint`'s worst ground flips to
     * `surface-raised` — which is why its contract names all three grounds.
     */
    @Test
    fun rosterRowsDark() = capture("roster-rows-dark") { RoomRowStatesDark() }

    /** The whole roster, sectioned as the core sectioned it. */
    @Test
    fun roster() = capture("roster") { RosterFurnished() }

    /**
     * Pending and answered are one preview in two files: the difference
     * between these frames is the entire visual grammar of "this needs you".
     */
    @Test
    fun cardPending() = capture("card-pending") { CardPending() }

    @Test
    fun cardAnswered() = capture("card-answered") { CardAnswered() }

    /**
     * A 71-character unbroken field value at a phone's width. The web story
     * for the same guard rendered 1147px wide on its first attempt *while
     * appearing to show the guard holding*.
     */
    @Test
    fun cardUnbreakableValue() = capture("card-unbreakable-value") { CardLongValue() }

    /**
     * A sender-controlled event type carrying U+202E. If it reorders itself
     * on screen that is a real defect reachable by anyone who can send to a
     * room — and nothing else in the catalogue would show it.
     */
    @Test
    fun cardHostileEventType() = capture("card-hostile-event-type") { CardHostileEventType() }

    /** Every rich block kind together, which is the only way to judge rhythm. */
    @Test
    fun richTextEveryBlock() = capture("rich-text-every-block") { RichTextEveryBlock() }

    /** The whole timeline vocabulary, where the run grouping is visible. */
    @Test
    fun timelineVocabulary() = capture("timeline-vocabulary") { TimelineVocabulary() }

    /** A 104-character unbroken body at a phone's width. */
    @Test
    fun timelineUnbreakableBody() = capture("timeline-unbreakable-body") { UnbreakableBody() }

    /**
     * A failed send is the one timeline row asking the reader for something,
     * and it may not use amber to do it — amber means a pending decision.
     */
    @Test
    fun timelineSendStates() = capture("timeline-send-states") { SendStates() }
}
