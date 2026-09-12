package dev.supermessage.previews

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.AccountPanel
import dev.supermessage.Composer
import dev.supermessage.InvitationEmptyTimeline
import dev.supermessage.InvitationView
import dev.supermessage.LoginScreen
import dev.supermessage.NewRoomPanel
import dev.supermessage.RoomInfoPanel
import dev.supermessage.RootScaffold
import dev.supermessage.SearchPanel
import dev.supermessage.SearchPanelScope
import dev.supermessage.kit.Session
import dev.supermessage.kit.stores.EditTarget
import dev.supermessage.kit.stores.ReplyTarget
import java.time.Instant
import uniffi.supermessage_core.AccountDto
import uniffi.supermessage_ffi.StagedFile

/** Fixed, for the reason `RosterPreviews.kt` gives. */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)

/**
 * ## Why every panel here takes lambdas rather than a session
 *
 * Android's composables were already props-down before this project started:
 * `AccountPanel` takes `loadAccount: suspend () -> AccountDto?`, not a
 * `Session`. So Android needed **no injection seam at all** — the whole of
 * `apple/SupermessageKit/CoreSeam.swift` and its eleven protocols exist to
 * give iOS what this file gets for free. That asymmetry is recorded in
 * `docs/platform-parity.md`; it is the largest structural difference between
 * the two native halves.
 *
 * The consequence for a preview is that a stub is one lambda per screen,
 * written at the call site, and there is nothing to fake underneath it.
 */

/** The composer at rest. */
@Preview(name = "Composer, empty", showBackground = true, heightDp = 140)
@Composable
private fun ComposerEmpty() {
    PreviewGround {
        Composer(text = "", onTextChange = {}, onSend = {})
    }
}

/** Mid-sentence, and sending. */
@Preview(name = "Composer, sending", showBackground = true, heightDp = 140)
@Composable
private fun ComposerSending() {
    PreviewGround {
        Composer(
            text = "Rebasing now, the token diff should come out empty.",
            onTextChange = {},
            onSend = {},
            sending = true,
        )
    }
}

/**
 * With a reply staged above it.
 *
 * `ReplyTarget.Pending` is a snapshot rather than a binding, on purpose: the
 * composer keeps whatever it was handed, so a parent later redacted or
 * scrolled out of the materialised timeline does not make this row change or
 * vanish underneath the person writing.
 */
@Preview(name = "Composer, replying", showBackground = true, heightDp = 180)
@Composable
private fun ComposerReplying() {
    PreviewGround {
        Composer(
            text = "",
            onTextChange = {},
            onSend = {},
            replyTo = ReplyTarget.Pending(
                eventId = "\$m1",
                sender = "Rakesh",
                excerpt = "Should the contrast contract list every ground?",
            ),
        )
    }
}

/**
 * Editing, which seeds the composer with what the message said.
 *
 * Previewed beside the reply state because the two share one banner and the
 * question is whether a reader can tell which of them they are in.
 */
@Preview(name = "Composer, editing", showBackground = true, heightDp = 180)
@Composable
private fun ComposerEditing() {
    PreviewGround {
        Composer(
            text = "Rebased onto main and the token diff is empty now.",
            onTextChange = {},
            onSend = {},
            editing = EditTarget.Pending(
                eventId = "\$m1",
                body = "Rebased onto main and the token diff is empty now.",
            ),
        )
    }
}

/** An attachment staged, with a filename longer than its room. */
@Preview(name = "Composer, attachment", showBackground = true, heightDp = 200)
@Composable
private fun ComposerAttachment() {
    PreviewGround {
        Composer(
            text = "",
            onTextChange = {},
            onSend = {},
            attachment = StagedFile(
                token = "staged-1",
                filename = "muster-dark-1500x900-final-for-real-this-time.png",
                sizeBytes = 184_320uL,
                mime = "image/png",
                width = 1500uL,
                height = 900uL,
            ),
        )
    }
}

/** A send that failed, which is the one composer state carrying `danger`. */
@Preview(name = "Composer, failed", showBackground = true, heightDp = 180)
@Composable
private fun ComposerFailed() {
    PreviewGround {
        Composer(
            text = "Merging it.",
            onTextChange = {},
            onSend = {},
            failure = "Could not reach the homeserver.",
        )
    }
}

/** The first screen anyone sees. */
@Preview(name = "Login", showBackground = true, heightDp = 640)
@Composable
private fun Login() {
    PreviewGround {
        LoginScreen(
            homeserver = "https://matrix.example.org",
            onHomeserverChange = {},
            failure = null,
            busy = false,
            onSignIn = { _, _ -> },
        )
    }
}

/**
 * Sign-in refused, in dark.
 *
 * Dark because this is the only screen a reader sees before the palette has
 * any content to be judged against, and the failure state is the only place
 * `danger` appears on it.
 */
@Preview(name = "Login, refused, dark", showBackground = true, heightDp = 640)
@Composable
private fun LoginRefusedDark() {
    PreviewGround(dark = true) {
        LoginScreen(
            homeserver = "https://matrix.example.org",
            onHomeserverChange = {},
            failure = "Incorrect username or password.",
            busy = false,
            onSignIn = { _, _ -> },
        )
    }
}

/** The account, which is two facts and a way out. */
@Preview(name = "Account", showBackground = true, heightDp = 420)
@Composable
private fun Account() {
    PreviewGround {
        AccountPanel(
            loadAccount = {
                AccountDto(
                    userId = "@rakesh:example.org",
                    homeserver = "https://matrix.example.org",
                )
            },
            onSignOut = {},
            onClose = {},
        )
    }
}

/**
 * A room with everything filled in.
 *
 * The runtime line — `claude-code on foundry` — is the suite's own fact about
 * a room, and this is the only screen that shows it in full. `avatarUri` is
 * null, so the identity's initial on a tinted disc is what renders, which is
 * what most rooms in this product actually show.
 */
@Preview(name = "Room info", showBackground = true, heightDp = 900)
@Composable
private fun RoomInfo() {
    PreviewGround {
        RoomInfoPanel(
            roomId = PreviewFixtures.ROOM_ID,
            accountUserId = "@rakesh:example.org",
            avatarUri = null,
            onClose = {},
            loadInfo = { PreviewFixtures.roomInfo },
            onSetNotifications = { true },
            onSetPinned = { true },
            onLeaveRoom = {},
        )
    }
}

/**
 * Search opened from inside a room, so it has a scope to offer.
 *
 * Idle: nothing typed yet, which is what a reader sees for as long as it
 * takes them to think of a word.
 */
@Preview(name = "Search, scoped", showBackground = true, heightDp = 640)
@Composable
private fun SearchScoped() {
    PreviewGround {
        SearchPanel(
            scope = SearchPanelScope(PreviewFixtures.ROOM_ID, "✳ Atlas — Platform"),
            onOpen = {},
            onClose = {},
            search = { _, _ -> PreviewFixtures.searchResults },
            roomName = { "✳ Atlas — Platform" },
            now = NOW,
        )
    }
}

/**
 * Search opened from nowhere in particular.
 *
 * `scope` is null, so the scope chips are absent entirely — one chip is a
 * label wearing a control's clothes. This is the layout without them.
 */
@Preview(name = "Search, unscoped", showBackground = true, heightDp = 640)
@Composable
private fun SearchUnscoped() {
    PreviewGround {
        SearchPanel(
            scope = null,
            onOpen = {},
            onClose = {},
            search = { _, _ -> PreviewFixtures.searchResults },
            now = NOW,
        )
    }
}

/**
 * The people this account knows, including one with no display name.
 *
 * That row falls back to the raw `@9247e5a1b3c4:id.agentpod.dev`, which is
 * both real and the longest thing the row will ever hold. An agent that has
 * not published a profile looks exactly like this.
 */
@Preview(name = "New room", showBackground = true, heightDp = 700)
@Composable
private fun NewRoom() {
    PreviewGround {
        NewRoomPanel(
            onOpen = {},
            onClose = {},
            loadPeople = { PreviewFixtures.people },
            openConversation = { Session.Outcome.Success(PreviewFixtures.ROOM_ID) },
            joinByAlias = { Session.Outcome.Success(PreviewFixtures.ROOM_ID) },
        )
    }
}

/**
 * An account that knows nobody, where "join by address" stops being an
 * alternative and becomes the only way forward.
 */
@Preview(name = "New room, nobody yet", showBackground = true, heightDp = 480)
@Composable
private fun NewRoomEmpty() {
    PreviewGround {
        NewRoomPanel(
            onOpen = {},
            onClose = {},
            loadPeople = { emptyList() },
            openConversation = { Session.Outcome.Failure("no one to talk to") },
            joinByAlias = { Session.Outcome.Success(PreviewFixtures.ROOM_ID) },
        )
    }
}

/**
 * An invitation with its inviter resolved to a raw id.
 *
 * The interesting case rather than a display name: an invitation from someone
 * whose profile this account has never seen is the common one, and the raw id
 * is what the reader is asked to make a decision about.
 */
@Preview(name = "Invitation", showBackground = true, heightDp = 480)
@Composable
private fun Invitation() {
    PreviewGround {
        InvitationView(
            roomId = "!estate:example.org",
            roomName = "Estate Planning",
            inviter = { "@krishna:example.org" },
            joinRoom = { null },
            leaveRoom = { null },
        )
    }
}

/** The empty timeline behind an invitation — a room not readable yet. */
@Preview(name = "Invitation, empty timeline", showBackground = true, heightDp = 320)
@Composable
private fun InvitationEmpty() {
    PreviewGround {
        InvitationEmptyTimeline()
    }
}

/**
 * The shell, in each of the three phases.
 *
 * `RootScaffold` defaults every pane to a labelled placeholder, which is what
 * these previews show: the question here is the *shell* — where the panes
 * sit, what a back gesture does, how the width is split — not what is in
 * them. Every pane's real content has its own previews above.
 */
@Preview(name = "Shell, signed in", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun ShellSignedIn() {
    PreviewGround {
        RootScaffold(phase = Session.Phase.SIGNED_IN)
    }
}

/**
 * Starting, which is a spinner — and a frame nobody has looked at, because in
 * a running app it lasts as long as a session restore takes.
 */
@Preview(name = "Shell, starting", showBackground = true, widthDp = 411, heightDp = 891)
@Composable
private fun ShellStarting() {
    PreviewGround {
        RootScaffold(phase = Session.Phase.STARTING)
    }
}

/**
 * A tablet's width, where the roster sits beside the timeline.
 *
 * The iOS half of this project previews the same arrangement for a recorded
 * reason: `NavigationSplitView`'s automatic default hid the roster on an iPad
 * in portrait, opening the app on an empty detail pane. Android splits its
 * own width rather than asking the OS, so the failure would look different —
 * but it is the same question, and this is where it gets asked.
 */
@Preview(name = "Shell, tablet", showBackground = true, widthDp = 1024, heightDp = 768)
@Composable
private fun ShellTablet() {
    PreviewGround {
        RootScaffold(phase = Session.Phase.SIGNED_IN)
    }
}
