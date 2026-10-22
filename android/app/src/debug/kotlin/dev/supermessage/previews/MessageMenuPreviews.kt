package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.supermessage.MessageMenuContent
import dev.supermessage.ReactionWhoCard
import uniffi.supermessage_core.ReactionDto

/** Someone else's message: the strip, Reply and Copy. */
@Preview(name = "Message menu, others", showBackground = true, heightDp = 330)
@Composable
internal fun MessageMenuOthers() {
    PreviewGround(width = 411.dp) {
        MessageMenuContent(PreviewFixtures.message, {}, {}, {}, {}, {}, {})
    }
}

/** Your own message: the strip, Reply, Copy, Edit and a destructive Delete. */
@Preview(name = "Message menu, own", showBackground = true, heightDp = 360)
@Composable
internal fun MessageMenuOwn() {
    PreviewGround(width = 411.dp) {
        MessageMenuContent(PreviewFixtures.ownLast, {}, {}, {}, {}, {}, {})
    }
}

/** Delete asks first. */
@Preview(name = "Delete confirm", showBackground = true, heightDp = 260)
@Composable
internal fun DeleteConfirm() {
    PreviewGround(width = 411.dp) {
        dev.supermessage.DeleteConfirm(onConfirm = {}, onDismiss = {})
    }
}

/** What a long press on a reaction chip shows. */
@Preview(name = "Reaction, who reacted", showBackground = true, heightDp = 120)
@Composable
internal fun ReactionWhoReacted() {
    PreviewGround(width = 411.dp) {
        ReactionWhoCard(ReactionDto("👍", "👍", 2u, false, listOf("@ganesha:example.org", "@priya:example.org")))
    }
}
