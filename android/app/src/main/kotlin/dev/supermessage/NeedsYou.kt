package dev.supermessage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlinx.coroutines.delay
import uniffi.supermessage_core.NeedsYouInbox
import uniffi.supermessage_core.RosterRow

/** Whether the caught-up seal will bounce, so a test can read it (a bounce has no other trace). */
internal val BouncesKey = SemanticsPropertyKey<Boolean>("Bounces")
internal var SemanticsPropertyReceiver.bounces by BouncesKey

/**
 * Needs you: every decision owed and every invitation waiting, and nothing
 * else (`Inbox/NeedsYouView.swift:11-57`).
 *
 * **Finishable.** [inbox] is `RosterArrangement.needsYouInbox`'s answer; a row
 * leaves when the core stops saying it needs you, so an empty inbox is a fact
 * rather than a filter. A section with no rows is left out.
 *
 * A `null` [inbox] is a roster not yet read: the pane draws nothing, in particular not
 * "You're all caught up" (iOS has no such state; the store starts empty and iOS reads it first).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NeedsYouPane(
    inbox: NeedsYouInbox?,
    now: Instant,
    showsState: Boolean,
    avatarUri: (String) -> String?,
    onLoadAvatar: (String) -> Unit,
    onOpenRoom: (String) -> Unit,
    onOpenInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Hoisted by the shell, so the scroll position outlives a visit to another destination. */
    listState: LazyListState = rememberLazyListState(),
) {
    val reduce = reducesMotion()

    @Composable
    fun androidx.compose.foundation.lazy.LazyItemScope.Entry(entry: RosterRow) {
        val roomId = entry.row.room.id
        RosterEntryRow(
            entry = entry,
            now = now,
            avatarUri = avatarUri(roomId),
            showsState = showsState,
            onOpenRoom = { onOpenRoom(roomId) },
            onOpenInfo = { onOpenInfo(roomId) },
            // A row leaving is the inbox being finished; let it be seen to go.
            modifier = Modifier.animateItem(
                fadeInSpec = motion(reduce, tween<Float>(150)),
                placementSpec = motion(reduce, spring<androidx.compose.ui.unit.IntOffset>()),
                fadeOutSpec = motion(reduce, tween<Float>(150)),
            ),
        )
        LaunchedEffect(roomId) { onLoadAvatar(roomId) }
    }

    // Not read yet is not the same as nothing to do: say neither until the roster has landed.
    if (inbox == null) {
        Box(modifier.fillMaxSize().testTag("needs-you-unread"))
        return
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("needs-you-list"), state = listState) {
            if (inbox.decisions.isNotEmpty()) {
                stickyHeader(key = "header-decisions") {
                    InboxHeader("Waiting on you", inbox.decisions.size, "decisions")
                }
                items(inbox.decisions, key = { "decision-${it.row.room.id}" }) { Entry(it) }
            }
            if (inbox.invitations.isNotEmpty()) {
                stickyHeader(key = "header-invitations") {
                    InboxHeader("Invitations", inbox.invitations.size, "invitations")
                }
                items(inbox.invitations, key = { "invitation-${it.row.room.id}" }) { Entry(it) }
            }
        }
        if (inbox.count == 0u) CaughtUp()
    }
}

/** A section's title and how many rows it has: meta in muted, then faint, not upper-cased (`NeedsYouView.swift:74-80`). */
@Composable
private fun InboxHeader(title: String, count: Int, tag: String) {
    val colors = SupermessageTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("needs-you-header-$tag")
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.contentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.contentFaint,
            maxLines = 1,
            modifier = Modifier.testTag("needs-you-count-$tag"),
        )
    }
}

/**
 * The empty inbox, which is the good outcome and should feel like one
 * (`NeedsYouView.swift:87-119`): one glyph, one bounce when it arrives, and
 * none under Reduce Motion — the words say it either way.
 */
@Composable
internal fun CaughtUp(modifier: Modifier = Modifier) {
    val colors = SupermessageTheme.colors
    val reduce = reducesMotion()
    val scale = remember { Animatable(1f) }
    // A beat after appearing, so the bounce is seen rather than happening during the switch.
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        delay(250)
        scale.animateTo(1.15f, tween(120))
        scale.animateTo(1f, spring())
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("caught-up")
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            // Material's `Verified` seal is in `-extended`, which this build leaves out; the check in a circle is core.
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = colors.ok,
            modifier = Modifier
                .testTag("caught-up-seal")
                .clearAndSetSemantics { bounces = !reduce }
                .size(56.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value },
        )
        Text(
            "You're all caught up",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            textAlign = TextAlign.Center,
        )
        Text(
            "Decisions and invitations that need you land here.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.contentMuted,
            textAlign = TextAlign.Center,
        )
    }
}
