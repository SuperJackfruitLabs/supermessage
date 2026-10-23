package dev.supermessage.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermessage.AcknowledgementDock
import dev.supermessage.LocalReduceMotion
import dev.supermessage.TypingPill
import uniffi.supermessage_core.Acknowledgement
import uniffi.supermessage_core.AcknowledgementKind

private fun sent(name: String) = Acknowledgement(AcknowledgementKind.SENT, listOf(name), "Sent to $name")
private fun onIt(text: String, vararg names: String) = Acknowledgement(AcknowledgementKind.ON_IT, names.toList(), text)

@Composable
private fun Everything() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = androidx.compose.ui.Modifier.padding(4.dp)) {
        AcknowledgementDock(sent("Atlas"))
        AcknowledgementDock(onIt("Atlas is on it…", "Atlas"))
        AcknowledgementDock(onIt("Atlas and Quill are on it…", "Atlas", "Quill"))
        TypingPill("Krishna is typing…")
    }
}


/** The two dock sentences and the typing pill (iOS `TimelineView.swift:279-291`); the ellipsis is still in a render. */
@Preview(name = "Acknowledgement", showBackground = true, heightDp = 190)
@Composable
internal fun DockSentAndOnIt() = CompositionLocalProvider(LocalReduceMotion provides true) {
    PreviewGround(width = 411.dp) { Everything() }
}

@Preview(name = "Acknowledgement, dark", showBackground = true, heightDp = 190)
@Composable
internal fun DockSentAndOnItDark() = CompositionLocalProvider(LocalReduceMotion provides true) {
    PreviewGround(dark = true, width = 411.dp) { Everything() }
}

/** Font scale 2.0: the longest sentence stays on one line and inside the screen. */
@Preview(name = "Dock, text 2.0", showBackground = true, heightDp = 190)
@Composable
internal fun DockLargeText() {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, fontScale = 2f),
        LocalReduceMotion provides true,
    ) {
        PreviewGround(width = 411.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AcknowledgementDock(onIt("Atlas, Quill and 2 others are on it…", "Atlas", "Quill"))
                TypingPill("Krishna, Pat and 2 others are typing…")
            }
        }
    }
}
