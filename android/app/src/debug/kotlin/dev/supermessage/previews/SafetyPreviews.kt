package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.supermessage.InvitationView
import dev.supermessage.MessageMenuContent
import dev.supermessage.ReportSheetBody
import dev.supermessage.kit.ReportSubject

/**
 * The report sheet's contents, drawn without the sheet chrome (a
 * `ModalBottomSheet` is a window of its own and does not photograph).
 */
@Composable
private fun Body(
    subject: ReportSubject,
    reason: String = "",
    failure: String? = null,
    remaining: Long = 1_500L,
    blocked: Set<String> = emptySet(),
) {
    PreviewGround(width = 411.dp) {
        ReportSheetBody(
            subject = subject,
            isBlocked = { it in blocked },
            onSend = { _, _ -> null },
            onClose = {},
            remaining = { remaining },
            initialReason = reason,
            initialFailure = failure,
        )
    }
}

/** An agent's message, a reason typed, the block switch on: the agent footer is the part to look at. */
@Preview(name = "Report message, agent", showBackground = true, heightDp = 500)
@Composable
internal fun ReportMessageAgent() {
    Body(
        subject = ReportSubject.Message("!r:example.org", "\$e", "@agent_atlas:example.org", "Atlas", isAgent = true),
        reason = "It posted a private key to the room.",
    )
}

/** A report the homeserver refused: nothing was sent, and the reason stays. */
@Preview(name = "Report person, refused", showBackground = true, heightDp = 500)
@Composable
internal fun ReportPersonRefused() {
    Body(
        subject = ReportSubject.User("@priya:example.org", "Priya", isAgent = false),
        reason = "Keeps inviting me to rooms I did not ask for.",
        failure = "This homeserver doesn't accept person reports. Nothing was sent.",
    )
}

/** A room: no block switch, because there is no one to block. */
@Preview(name = "Report room", showBackground = true, heightDp = 380)
@Composable
internal fun ReportRoom() {
    Body(subject = ReportSubject.Room("!lounge:example.org", "Lounge"))
}

/** Over the limit: the counter is in danger and Send is disabled. */
@Preview(name = "Report over the limit", showBackground = true, heightDp = 520)
@Composable
internal fun ReportOverLimit() {
    Body(
        subject = ReportSubject.Message("!r:example.org", "\$e", "@priya:example.org", "Priya", isAgent = false),
        reason = "A very long account of what happened.",
        remaining = -12L,
    )
}

/** The report landed and the block did not: "Report sent", the block failure, and only Done. */
@Preview(name = "Report sent, block failed", showBackground = true, heightDp = 130)
@Composable
internal fun ReportSentBlockFailed() {
    Body(
        subject = ReportSubject.Message("!r:example.org", "\$e", "@priya:example.org", "Priya", isAgent = false),
        failure = "Reported, but blocking didn't work: You can't block yourself.",
    )
}

/** A message with no text: Report… still makes it a menu, last and destructive. */
@Preview(name = "Message menu, with report", showBackground = true, heightDp = 200)
@Composable
internal fun MessageMenuWithReport() {
    val bare = PreviewFixtures.message.let { it.copy(item = it.item.copy(body = null, editable = false)) }
    PreviewGround(width = 411.dp) {
        MessageMenuContent(bare, {}, {}, {}, {}, {}, {})
    }
}

/** The quiet "Report room…" under Accept and Decline. */
@Preview(name = "Invitation, with report", showBackground = true, heightDp = 260)
@Composable
internal fun InvitationWithReport() {
    PreviewGround {
        InvitationView(
            roomId = "!estate:example.org",
            roomName = "Estate Planning",
            inviter = { null },
            joinRoom = { null },
            leaveRoom = { null },
            onReport = {},
        )
    }
}
