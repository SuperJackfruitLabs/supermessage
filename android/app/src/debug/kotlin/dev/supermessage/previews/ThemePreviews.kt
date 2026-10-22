package dev.supermessage.previews

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.AppearanceMode
import dev.supermessage.AppearanceSettings
import dev.supermessage.DarkStyle
import androidx.compose.material3.Surface
import dev.supermessage.RoomRow
import dev.supermessage.SupermessageTheme
import dev.supermessage.ThemeChoice
import uniffi.supermessage_core.AgentState

/** The chooser itself: mode, dark style and the accent row, in the house palette. */
@Preview(name = "AccountAppearance", showBackground = true, heightDp = 360)
@Composable
internal fun AccountAppearance() {
    PreviewGround {
        OnSurface {
            AppearanceSettings(choice = ThemeChoice(), onMode = {}, onDarkStyle = {}, onAccent = {})
        }
    }
}

/**
 * Black with an accent. The ground must be true black and only the accent
 * roles move (the desktop's version let the accent override Black's surfaces).
 */
@Preview(name = "ThemeBlackDark", showBackground = true, heightDp = 600)
@Composable
internal fun ThemeBlackDark() {
    val choice = ThemeChoice(AppearanceMode.DARK, DarkStyle.BLACK, "pink")
    PreviewGround(choice = choice) {
        OnSurface { Column {
            AppearanceSettings(choice = choice, onMode = {}, onDarkStyle = {}, onAccent = {})
            RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m")
            RoomRow(PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m")
        } }
    }
}

/** A non-default accent in the light appearance. */
@Preview(name = "ThemeBlueLight", showBackground = true, heightDp = 600)
@Composable
internal fun ThemeBlueLight() {
    val choice = ThemeChoice(AppearanceMode.LIGHT, DarkStyle.TINTED, "blue")
    PreviewGround(choice = choice) {
        OnSurface { Column {
            AppearanceSettings(choice = choice, onMode = {}, onDarkStyle = {}, onAccent = {})
            RoomRow(PreviewFixtures.roomNeedsYou, null, AgentState.NEEDS_YOU, "2m")
            RoomRow(PreviewFixtures.roomActive, null, AgentState.ACTIVE, "14m")
        } }
    }
}

/** What the app's own `Surface` gives its content: the text colour of the palette in force. */
@Composable
private fun OnSurface(content: @Composable () -> Unit) {
    Surface(color = SupermessageTheme.colors.surface, content = content)
}
