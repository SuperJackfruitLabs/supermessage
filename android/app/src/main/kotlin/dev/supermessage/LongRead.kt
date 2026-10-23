package dev.supermessage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import uniffi.supermessage_core.RichBlock

/** What a screen reader says the "Read" button does (`TimelineRowView.swift:451`). */
private const val ReadHint = "Opens the whole message in a reading view"

/**
 * A long message's body inside its card (`TimelineRowView.swift:423-457`): the
 * text at its full height, **cut** at 320 with a fade from opaque at 72 % to
 * clear, and under it a "Read" button that opens [LongReadView]. Whether a
 * message is long is the core's call (`row.isLongRead`); this only draws it.
 */
@Composable
internal fun LongReadBody(blocks: List<RichBlock>, fontFamily: FontFamily, title: String) {
    var reading by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // `wrapContentHeight(unbounded = true)` first: the text lays out at its
        // full height and the 320 then cuts it, instead of every paragraph
        // shrinking to fit an offer.
        Box(
            Modifier
                .testTag("long-read-cut")
                .heightIn(max = 320.dp)
                .clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black, 0.72f to Color.Black, 1f to Color.Transparent,
                            startY = 0f, endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            Box(Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true)) {
                RichText(blocks = blocks, fontFamily = fontFamily)
            }
        }
        Row(
            modifier = Modifier
                .testTag("long-read-button")
                .clickable(onClickLabel = ReadHint, role = Role.Button) { reading = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(BookGlyph, contentDescription = null, tint = SupermessageTheme.colors.accent, modifier = Modifier.size(18.dp))
            Text(
                "Read",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = ThemeType.ui,
                fontWeight = FontWeight.SemiBold,
                color = SupermessageTheme.colors.accent,
            )
        }
    }
    if (reading) LongReadView(title = title, blocks = blocks, onDone = { reading = false })
}

/**
 * An agent's report, set for reading (`LongReadSheet`, `TimelineChrome.swift:192-225`):
 * the whole message through the same renderer in the serif
 * [SupermessageTypography.longread], line spacing +4, at most 640 wide,
 * padding 20 / 16, on `surface`, with "Done" to close. A bottom sheet that
 * opens full (Decision 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongReadView(title: String, blocks: List<RichBlock>, onDone: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SupermessageTheme.colors.surface,
    ) {
        LongReadContent(title = title, blocks = blocks, onDone = onDone)
    }
}

/** The sheet's content, apart from its window so a preview can draw it. */
@Composable
internal fun LongReadContent(title: String, blocks: List<RichBlock>, onDone: () -> Unit) {
    val colors = SupermessageTheme.colors
    Column(Modifier.fillMaxWidth().testTag("long-read-view")) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onDone) { Text("Done", color = colors.accent) }
        }
        val base = MaterialTheme.typography.bodyLarge
        val spaced = base.copy(
            fontFamily = SupermessageTheme.typography.longread,
            lineHeight = ((if (base.lineHeight.isSpecified) base.lineHeight.value else base.fontSize.value * 1.4f) + 4f).sp,
            color = colors.content,
        )
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            CompositionLocalProvider(LocalTextStyle provides spaced) {
                RichText(
                    blocks = blocks,
                    fontFamily = SupermessageTheme.typography.longread,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 640.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/** The book glyph (Material "menu_book"); icons-core has no book. */
private val BookGlyph: ImageVector = materialIcon(name = "Book") {
    materialPath {
        moveTo(21f, 5f)
        curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
        curveToRelative(-1.95f, 0f, -4.05f, 0.4f, -5.5f, 1.5f)
        curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
        reflectiveCurveTo(2.45f, 4.9f, 1f, 6f)
        verticalLineToRelative(14.65f)
        curveToRelative(0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
        curveToRelative(0.1f, 0f, 0.15f, -0.05f, 0.25f, -0.05f)
        curveTo(3.1f, 20.45f, 5.05f, 20f, 6.5f, 20f)
        curveToRelative(1.95f, 0f, 4.05f, 0.4f, 5.5f, 1.5f)
        curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
        curveToRelative(1.65f, 0f, 3.35f, 0.3f, 4.75f, 1.05f)
        curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
        curveToRelative(0.25f, 0f, 0.5f, -0.25f, 0.5f, -0.5f)
        verticalLineTo(6f)
        curveToRelative(-0.6f, -0.45f, -1.25f, -0.75f, -2f, -1f)
        close()
        moveTo(21f, 18.5f)
        curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
        curveToRelative(-1.7f, 0f, -4.15f, 0.65f, -5.5f, 1.5f)
        verticalLineTo(8f)
        curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
        curveToRelative(1.2f, 0f, 2.4f, 0.15f, 3.5f, 0.5f)
        verticalLineToRelative(11.5f)
        close()
    }
}
