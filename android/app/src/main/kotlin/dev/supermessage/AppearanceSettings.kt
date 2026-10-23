package dev.supermessage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** What the swatch row offers: the house violet first, as `null`, then the generated accents. */
private val AccentOptions: List<String?> = listOf<String?>(null) + GeneratedAccents.names

internal fun accentLabel(name: String?): String = name?.replaceFirstChar { it.uppercase() } ?: "Violet"

internal fun accentTag(name: String?): String = "accent-${name ?: "violet"}"

/**
 * The Appearance section of the Account panel (`AccountPanel.swift:82-96`,
 * `:221-262`): mode, dark style and the accent swatches, with iOS's footer.
 * It is handed to [AccountPanel] through its `appearance` slot.
 */
@Composable
fun AppearanceSettings(
    choice: ThemeChoice,
    onMode: suspend (AppearanceMode) -> Unit,
    onDarkStyle: suspend (DarkStyle) -> Unit,
    onAccent: suspend (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().testTag("account-appearance")) {
        AccountSectionHeader("Appearance")
        Segmented(
            label = "Appearance",
            options = AppearanceMode.entries,
            selected = choice.mode,
            text = { it.label },
            tag = { "appearance-mode-${it.raw}" },
            onSelect = { scope.launch { onMode(it) } },
        )
        Segmented(
            label = "Dark style",
            options = DarkStyle.entries,
            selected = choice.darkStyle,
            text = { it.label },
            tag = { "appearance-dark-${it.raw}" },
            onSelect = { scope.launch { onDarkStyle(it) } },
        )
        AccentSwatches(choice = choice, onAccent = { scope.launch { onAccent(it) } })
        AccountSectionFooter(
            "Black is true black in dark mode, for OLED screens. The accent colours buttons, links and your own highlights.",
        )
    }
}

@Composable
private fun <T> Segmented(
    label: String,
    options: List<T>,
    selected: T,
    text: (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.testTag(tag(option)),
                    label = { Text(text(option), maxLines = 1) },
                )
            }
        }
    }
}

/**
 * Each swatch is drawn in its own colour for the appearance in force, so what
 * is offered is what will be seen; the selection ring is `content`, not the
 * accent, because the accent is what is being chosen. 30dp in a 44dp target.
 */
@Composable
private fun AccentSwatches(choice: ThemeChoice, onAccent: (String?) -> Unit) {
    val dark = LocalSupermessageDark.current
    val colors = SupermessageTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { contentDescription = "Accent" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AccentOptions.forEach { name ->
            val isSelected = choice.accent == name
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .testTag(accentTag(name))
                    .semantics {
                        role = Role.RadioButton
                        contentDescription = accentLabel(name)
                        selected = isSelected
                    }
                    .clickable { onAccent(name) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .padding(4.dp)
                        .then(
                            if (isSelected) Modifier.border(BorderStroke(2.dp, colors.content), CircleShape)
                            else Modifier,
                        )
                        .padding(4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(choice.copy(accent = name).palette(dark).accent),
                )
            }
        }
    }
}
