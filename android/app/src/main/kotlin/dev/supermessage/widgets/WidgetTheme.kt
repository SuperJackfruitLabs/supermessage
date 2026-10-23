package dev.supermessage.widgets

import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import dev.supermessage.GeneratedThemeTokens
import dev.supermessage.SupermessageColorRoles

/**
 * The palette, for the widgets (`SupermessageWidgets/Views/WidgetTheme.swift`).
 *
 * The same generated tokens the app's theme is made from: `paper` (light) by
 * day, `dark` by night. Glance is not Compose UI, so each role is a Glance
 * [ColorProvider]; no literal colour appears in a widget.
 */
internal object WidgetColors {
    private fun role(pick: (SupermessageColorRoles) -> Color): ColorProvider =
        ColorProvider(day = pick(GeneratedThemeTokens.light), night = pick(GeneratedThemeTokens.dark))

    val surface = role { it.surface }
    val surfaceSunken = role { it.surfaceSunken }
    val border = role { it.border }
    val content = role { it.content }
    val contentMuted = role { it.contentMuted }
    val contentFaint = role { it.contentFaint }
    val accent = role { it.accent }
    val danger = role { it.danger }
    val ok = role { it.ok }

    /**
     * Amber, and its ground. **Only [WidgetDecisionCard] may draw these**, and only while the
     * decision is still owed (docs/design-language.md section 2). A count of decisions, a
     * decision already sent: neither is a pending decision, and they use the accent or the muted rank.
     */
    val signal = role { it.signal }
    val signalSoft = role { it.signalSoft }
}
