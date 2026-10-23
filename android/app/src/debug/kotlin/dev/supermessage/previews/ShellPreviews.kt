package dev.supermessage.previews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.ChatsToolbar
import dev.supermessage.Destination
import dev.supermessage.Roster
import dev.supermessage.RootScaffold
import dev.supermessage.ShellNavigation
import dev.supermessage.SupermessageTheme
import dev.supermessage.ThemeChoice
import dev.supermessage.AppearanceMode
import dev.supermessage.kit.RosterArrangement
import dev.supermessage.kit.RosterChoice
import java.time.Instant

/**
 * The destinations' shell (N1): a bar under the roster on a phone, a rail beside it on a
 * tablet, with the Needs you badge over the iOS fleet ("Tabs, dark", "iPad fleet",
 * `RootView.swift:268-278`).
 */
private val NOW: Instant = Instant.ofEpochMilli(1_757_700_120_000L)
private val NOW_MS: ULong = NOW.toEpochMilli().toULong()

@Composable
private fun ShellFrame(dark: Boolean = false, selected: Destination = Destination.CHATS) {
    val rows = PreviewFixtures.fleet(NOW_MS)
    val inbox = RosterArrangement.needsYouInbox(rows, NOW)
    SupermessageTheme(choice = ThemeChoice(mode = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT)) {
        CompositionLocalProvider(LocalContentColor provides SupermessageTheme.colors.content) {
          // The app's Surface paints this; a bare preview does not.
          Box(Modifier.fillMaxSize().background(SupermessageTheme.colors.surface)) {
            RootScaffold(
                navigation = ShellNavigation(selected = selected, onSelect = {}, badgeCount = inbox.badgeCount.toInt()),
                listPaneContent = { _, _, _ ->
                    Column(Modifier.fillMaxSize()) {
                        ChatsToolbar(
                            accountInitial = "R",
                            onAccount = {},
                            onCompose = {},
                            title = { Text("Chats", style = MaterialTheme.typography.titleLarge) },
                        )
                        Roster(
                            sections = RosterArrangement.sections(rows, RosterChoice.WAITING, false, NOW),
                            invitations = emptyList(),
                            invitationsOpen = false,
                            onToggleInvitations = {},
                            now = NOW,
                            avatarUri = { null },
                            modifier = Modifier.weight(1f),
                        )
                    }
                },
                detailPaneContent = { _, _ -> Box(Modifier.fillMaxSize()) },
            )
          }
        }
    }
}

@Preview(name = "Shell, phone", showBackground = true, widthDp = 411, heightDp = 780)
@Composable
internal fun ShellPhone() = ShellFrame()

@Preview(name = "Shell, phone, dark", showBackground = true, widthDp = 411, heightDp = 780)
@Composable
internal fun ShellPhoneDark() = ShellFrame(dark = true, selected = Destination.NEEDS_YOU)

@Preview(name = "Shell, tablet rail", showBackground = true, widthDp = 840, heightDp = 640)
@Composable
internal fun ShellTabletRail() = ShellFrame()

@Preview(name = "Shell, tablet rail, dark", showBackground = true, widthDp = 840, heightDp = 640)
@Composable
internal fun ShellTabletRailDark() = ShellFrame(dark = true, selected = Destination.AGENTS)

@Preview(name = "Shell, phone, 2x", showBackground = true, widthDp = 411, heightDp = 900, fontScale = 2f)
@Composable
internal fun ShellLargeText() = ShellFrame()
