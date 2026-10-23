package dev.supermessage

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermessage.kit.word
import uniffi.supermessage_core.AgentAvatarStyle
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.AvatarRole

/** The design-language colour the core's tile names (`RoomAvatar.swift`'s role map). */
@Composable
internal fun avatarColour(role: AvatarRole): Color {
    val c = SupermessageTheme.colors
    return when (role) {
        AvatarRole.ACCENT -> c.accent
        AvatarRole.ACCENT_SOFT -> c.accentSoft
        AvatarRole.ACCENT_CONTENT -> c.accentContent
        AvatarRole.OK -> c.ok
        AvatarRole.CONTENT_MUTED -> c.contentMuted
        AvatarRole.SURFACE -> c.surface
        AvatarRole.SURFACE_RAISED -> c.surfaceRaised
    }
}

/**
 * A room's face (`Rooms/RoomAvatar.swift:12-118`): an agent's room is a
 * rounded square in the core's tile ([style]) with a sparkle badge; a room of
 * people is a circle. A picture fills the shape. The state dot sits at the
 * bottom corner and Quiet draws nothing; pass `state = null` to draw none.
 * M5's Agents view reuses this.
 */
@Composable
fun RoomAvatar(
    roomId: String,
    initial: String,
    avatarUri: String?,
    describesAgent: Boolean,
    style: AgentAvatarStyle?,
    state: AgentState?,
    size: Dp = 40.dp,
    onOpenInfo: (() -> Unit)? = null,
    infoLabel: String? = null,
) {
    val colors = SupermessageTheme.colors
    val bitmap = remember(avatarUri) { avatarUri?.decodeDataUri() }
    val shape = if (describesAgent) RoundedCornerShape(size * 0.28f) else CircleShape
    val ground = if (describesAgent && style != null) avatarColour(style.ground) else colors.surfaceRaised
    val ink = if (describesAgent && style != null) avatarColour(style.ink) else colors.contentMuted
    val ringed = ground == colors.surfaceRaised
    val dot = state != null && state != AgentState.QUIET
    val spoken = listOfNotNull(
        "Agent".takeIf { describesAgent },
        state?.takeIf { dot }?.word,
    ).joinToString(", ")

    var modifier = Modifier
        .testTag("avatar")
        .size(size)
        .semantics { if (spoken.isNotEmpty()) contentDescription = spoken }
    if (onOpenInfo != null) {
        modifier = modifier.clickable(onClickLabel = infoLabel, onClick = onOpenInfo)
    }

    Box(modifier) {
        var face = Modifier.fillMaxSize().clip(shape).background(ground)
        if (ringed) face = face.border(1.dp, colors.border, shape)
        Box(face, contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                // Fixed against the font scale, as the tile is: it is a picture of a letter.
                val px = with(LocalDensity.current) { (size * (if (describesAgent) 0.44f else 0.42f)).toSp() }
                Text(
                    initial,
                    style = TextStyle(
                        fontSize = px,
                        fontWeight = if (describesAgent) FontWeight.SemiBold else FontWeight.Medium,
                        color = ink,
                    ),
                )
            }
        }
        if (describesAgent) {
            val badge = size * 0.36f
            Box(
                Modifier
                    .testTag("agent-badge")
                    .align(Alignment.TopEnd)
                    .offset(3.dp, (-3).dp)
                    .size(badge)
                    .background(colors.surface, CircleShape)
                    .padding(1.5.dp)
                    .background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Sparkle, null, Modifier.size(badge * 0.6f), tint = colors.accentContent)
            }
        }
        if (dot) {
            Box(
                Modifier
                    .testTag("state-dot")
                    .align(Alignment.BottomEnd)
                    .offset(2.dp, 2.dp)
                    .size(size * 0.3f)
                    .background(colors.surface, CircleShape)
                    .padding(2.dp)
                    .background(
                        when (state) {
                            AgentState.NEEDS_YOU -> colors.signal
                            AgentState.ACTIVE -> colors.ok
                            else -> colors.contentFaint
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

/**
 * The sparkle (Material's "auto awesome"). The icon set here is `-core`, which
 * leaves that glyph out, so its path is drawn directly rather than pulling in
 * `-extended` for one shape.
 */
private val Sparkle: ImageVector = ImageVector.Builder(
    name = "Sparkle", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
).addPath(
    pathData = addPathNodes(
        "M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9z " +
            "M11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12L11.5,9.5z " +
            "M19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z",
    ),
    fill = SolidColor(Color.Black),
).build()
