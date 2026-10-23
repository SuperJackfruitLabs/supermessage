package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuite
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The four places (`Navigation/AppDestination.swift:6-28`), in this order. A room is not
 * one: it is a place the destination led to, and hides the bar while it is open.
 */
enum class Destination(val title: String) {
    CHATS("Chats"),
    NEEDS_YOU("Needs you"),
    AGENTS("Agents"),
    SEARCH("Search"),
}

/** What the shell needs to draw the destinations: where we are, how to move, and the badge. */
internal data class ShellNavigation(
    val selected: Destination,
    val onSelect: (Destination) -> Unit,
    /** Decisions only (`badge_count`), never invitations; nothing is drawn at 0. */
    val badgeCount: Int,
)

/**
 * A bar below 600dp, a rail from there, and nothing while a room fills a one-pane shell.
 * Measured width, never the window's size class (the same rule as [paneCountFor]).
 */
internal fun navLayoutFor(shellWidth: Dp, detailIsCurrentOnOnePane: Boolean): NavigationSuiteType = when {
    detailIsCurrentOnOnePane -> NavigationSuiteType.None
    shellWidth < TwoPaneWidth -> NavigationSuiteType.NavigationBar
    else -> NavigationSuiteType.NavigationRail
}

/** The rail's own width (Material 3 `NavigationRail`), taken off the shell before the panes are counted. */
internal val RailWidth: Dp = 80.dp

/** Whether the destinations stand beside the list as a rail (a tablet), rather than under it (a phone). */
internal val LocalNavigationRail = compositionLocalOf { false }

/** "Needs you, 2" when there are decisions, else the destination's name (`SplitShell.swift:192-194`). */
internal fun destinationLabel(destination: Destination, badgeCount: Int): String =
    if (destination == Destination.NEEDS_YOU && badgeCount > 0) "${destination.title}, $badgeCount" else destination.title

internal fun destinationTag(destination: Destination): String = when (destination) {
    Destination.CHATS -> "destination-chats"
    Destination.NEEDS_YOU -> "destination-needs-you"
    Destination.AGENTS -> "destination-agents"
    Destination.SEARCH -> "destination-search"
}

private fun iconFor(destination: Destination, selected: Boolean): ImageVector = when (destination) {
    Destination.CHATS -> if (selected) Icons.Filled.Forum else Icons.Outlined.Forum
    Destination.NEEDS_YOU -> if (selected) Icons.Filled.Inbox else Icons.Outlined.Inbox
    Destination.AGENTS -> if (selected) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome
    Destination.SEARCH -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
}

/** Outlined when idle, filled when selected; Needs you carries the badge. */
@Composable
internal fun DestinationIcon(destination: Destination, selected: Boolean, badgeCount: Int) {
    val glyph: @Composable () -> Unit = { Icon(iconFor(destination, selected), contentDescription = null) }
    if (destination == Destination.NEEDS_YOU && badgeCount > 0) {
        BadgedBox(badge = { NeedsYouBadge(badgeCount) }, content = { glyph() })
    } else {
        glyph()
    }
}

/** `accentContent` on an `accent` capsule, caption2 semibold, tabular digits (`SplitShell.swift:170-180`). Not amber. */
@Composable
internal fun NeedsYouBadge(count: Int) {
    val colors = SupermessageTheme.colors
    Badge(
        containerColor = colors.accent,
        contentColor = colors.accentContent,
    ) {
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.testTag("needs-you-badge"),
        )
    }
}

/**
 * The bar or rail itself. Tagged `nav-bar` or `nav-rail` on the container so the
 * layout can be asserted; each item says "Needs you, 2" and is exposed as selected
 * when it is the current place.
 */
@Composable
internal fun ShellNavigationSuite(navigation: ShellNavigation, layoutType: NavigationSuiteType) {
    val colors = SupermessageTheme.colors
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = colors.accent,
            selectedTextColor = colors.accent,
            indicatorColor = colors.accentSoft,
            unselectedIconColor = colors.contentMuted,
            unselectedTextColor = colors.contentMuted,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = colors.accent,
            selectedTextColor = colors.accent,
            indicatorColor = colors.accentSoft,
            unselectedIconColor = colors.contentMuted,
            unselectedTextColor = colors.contentMuted,
        ),
    )
    NavigationSuite(
        modifier = Modifier.testTag(if (layoutType == NavigationSuiteType.NavigationRail) "nav-rail" else "nav-bar"),
        layoutType = layoutType,
        colors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = colors.surface,
            navigationRailContainerColor = colors.surface,
        ),
    ) {
        Destination.entries.forEach { destination ->
            val selected = destination == navigation.selected
            val label = destinationLabel(destination, navigation.badgeCount)
            item(
                selected = selected,
                onClick = { navigation.onSelect(destination) },
                icon = { DestinationIcon(destination, selected, navigation.badgeCount) },
                label = { NavigationLabel(destination.title) },
                modifier = Modifier
                    .testTag(destinationTag(destination))
                    .semantics {
                        contentDescription = label
                        // A count that changes while the reader is here is spoken, not just found.
                        if (destination == Destination.NEEDS_YOU) liveRegion = LiveRegionMode.Polite
                    },
                colors = itemColors,
            )
        }
    }
}

/**
 * One word under a glyph, which has a quarter of the screen to be in. At the largest text sizes
 * "Needs you" no longer fits its quarter (it read "Needs…"), so the bar's labels stop growing
 * at 1.3x, as Material's own bar does for its container; everything else keeps scaling.
 */
@Composable
private fun NavigationLabel(title: String) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MaxBarFontScale))) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val MaxBarFontScale = 1.3f

/**
 * The Chats toolbar (`AppDestination.swift:48-107`): the account at the leading edge as its
 * initial in an `accent` circle, compose at the trailing edge. [title] sits between them.
 */
@Composable
internal fun ChatsToolbar(
    accountInitial: String,
    onAccount: () -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit = {},
) {
    val colors = SupermessageTheme.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAccount)
                .semantics { contentDescription = "Account" }
                .testTag("toolbar-account"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    accountInitial,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) { title() }
        IconButton(onClick = onCompose, modifier = Modifier.testTag("toolbar-new-conversation")) {
            Icon(Icons.Filled.Edit, contentDescription = "New conversation", tint = colors.accent)
        }
    }
}
