package dev.supermessage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import dev.supermessage.kit.ErrorPresenter
import dev.supermessage.kit.RelativeTime
import dev.supermessage.kit.SearchState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import uniffi.supermessage_core.SearchResultDto
import uniffi.supermessage_ffi.FfiException

/**
 * The room a search was opened from, so it can be offered as a narrower
 * choice than every room this account can see. `null` (no scope offered)
 * means the reader opened search with nothing to narrow to, and every
 * search runs unscoped.
 */
data class SearchPanelScope(val roomId: String, val name: String)

/**
 * Search across rooms, or within one — the port of
 * `apple/Supermessage/Panels/SearchPanel.swift`.
 *
 * ## No store behind this one
 *
 * The same shape [RoomInfoPanel] documents on its own KDoc: a search is a
 * request the core answers once, not a diff-driven stream, so this
 * composable holds its own `term`/[SearchState] rather than reading a
 * `StateFlow`. [search] stands in for `Session.search`, which already
 * calls [search] directly, in the same shape [RoomInfoPanel] documents on its
 * own KDoc. `search` — `Session.search` in production — no longer swallows a
 * `searchMessages` failure into an empty list; it lets it through, and this
 * file is the one that catches it, the same way `RoomInfoPanel.reload` catches
 * `loadInfo`'s. A refused search and a search with nothing are deliberately
 * *not* the same state: see [SearchState.Failed].
 *
 * ## What this panel does not decide
 *
 * `searchMessages` returns hits already in the order the core chose —
 * [SearchState.Found] renders that [List] exactly as handed to it. Sorting,
 * re-ranking or de-duplicating here would be a second, disagreeing
 * implementation of relevance sitting beside the one implementation that is
 * allowed to exist. [roomName] is the only per-result lookup this file does
 * itself, and it is a display fallback (the room's parsed name over its raw
 * id), never a re-derivation of what a hit *is* or where it belongs in the
 * list.
 *
 * Four states a reader must be able to tell apart — the reason
 * [dev.supermessage.kit.SearchState] exists rather than a pair of booleans,
 * see its own KDoc — are exactly [SearchState.Searching] (in flight),
 * [SearchState.Empty] (ran, found nothing), [SearchState.Found] (ran, found
 * something, in core order) and [SearchState.Failed] (ran, could not be
 * answered at all). [SearchState.Idle] and [SearchState.Ready] cover what
 * happens before a search has run at all.
 *
 * @param scope The room search was opened from, when it was opened from
 *   one. `null` offers no narrowing and every search is unscoped.
 * @param onOpen Opens the room a tapped result belongs to.
 * @param onClose Abandons the search — the panel's own way back, the same
 *   role `onClose` plays on [RoomInfoPanel].
 * @param search Runs a query, scoped to a room or not. May throw — this file
 *   catches that itself and maps it through `ErrorPresenter` into
 *   [SearchState.Failed], the same contract `Session.search` and
 *   `RoomInfoPanel`'s `loadInfo` already keep.
 * @param roomName Names a result's room for display, `null` when this
 *   account has no cached name for it (the row falls back to the raw room
 *   id, never inventing one).
 * @param now The instant relative times are measured against — a parameter
 *   rather than a clock read in place, the same seam
 *   [dev.supermessage.kit.RelativeTime] and `Roster`'s own `now` already use,
 *   so a result's "when" is fixed rather than racing the real clock in a
 *   test.
 */
@Composable
fun SearchPanel(
    scope: SearchPanelScope?,
    onOpen: (roomId: String) -> Unit,
    onClose: () -> Unit,
    search: suspend (term: String, roomId: String?) -> List<SearchResultDto>,
    roomName: (roomId: String) -> String? = { null },
    now: Instant = Instant.now(),
    modifier: Modifier = Modifier,
) {
    var term by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    // Whether the search is narrowed to `scope`. Starts narrowed: a reader
    // who opened search from inside a room is asking about that room —
    // the same default `SearchPanel.swift`'s own `narrowed` documents.
    var narrowed by remember { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()

    suspend fun run() {
        val query = term.trim()
        if (query.isEmpty()) return
        state = SearchState.Searching(query)
        state = try {
            val results = search(query, if (narrowed) scope?.roomId else null)
            if (results.isEmpty()) SearchState.Empty(query) else SearchState.Found(results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FfiException) {
            SearchState.Failed(query, ErrorPresenter.message(e))
        } catch (e: Exception) {
            SearchState.Failed(query, "Couldn't search.")
        }
    }

    fun rerunIfAlreadyAsked() {
        // Changing where to look re-asks rather than leaving the old
        // scope's results under the new scope's label — matching the guard
        // on `SearchPanel.swift`'s own `onChange(of: narrowed)`.
        if (state.query.isEmpty() && term.isBlank()) return
        coroutineScope.launch { run() }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Search", style = MaterialTheme.typography.titleMedium)
            // Cancel, not Done: nothing here is being composed, and the
            // only thing this button does is abandon the search.
            TextButton(onClick = onClose, modifier = Modifier.testTag("search-cancel")) { Text("Cancel") }
        }

        OutlinedTextField(
            value = term,
            onValueChange = { next ->
                term = next
                state = state.typed(next)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("search-field"),
            placeholder = { Text("Search") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { coroutineScope.launch { run() } }),
        )

        if (scope != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScopeChip(
                    label = scope.name,
                    selected = narrowed,
                    testTag = "search-scope-room",
                    onClick = { if (!narrowed) { narrowed = true; rerunIfAlreadyAsked() } },
                )
                ScopeChip(
                    label = "All rooms",
                    selected = !narrowed,
                    testTag = "search-scope-all",
                    onClick = { if (narrowed) { narrowed = false; rerunIfAlreadyAsked() } },
                )
            }
        }

        Box(Modifier.fillMaxSize()) {
            // Exhaustive over every SearchState subtype, on purpose — an
            // `else` here would silently swallow a sixth state some later
            // change to SearchState added, the same reasoning that keeps a
            // `when` over a core sealed class free of one.
            when (val current = state) {
                is SearchState.Idle ->
                    StatusMessage(
                        title = "Search",
                        detail = searchingWhere(scope, narrowed),
                        testTag = "search-idle",
                    )

                is SearchState.Ready ->
                    StatusMessage(
                        title = "Search for ${current.q}",
                        detail = "Press search to look.",
                        testTag = "search-ready",
                    )

                is SearchState.Searching ->
                    Column(
                        Modifier.fillMaxSize().testTag("search-loading"),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Searching…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                is SearchState.Empty ->
                    StatusMessage(
                        title = "No results",
                        detail = "Nothing found for \"${current.q}\".",
                        testTag = "search-empty",
                    )

                is SearchState.Failed ->
                    // Distinct from search-empty on purpose: this is the
                    // state that did not exist before this task, and its
                    // absence was the whole defect — a refused search read
                    // as "no results" instead of as a refusal.
                    StatusMessage(
                        title = "Couldn't search",
                        detail = current.message,
                        testTag = "search-failed",
                    )

                is SearchState.Found ->
                    LazyColumn(Modifier.fillMaxSize().testTag("search-results")) {
                        items(current.results, key = { it.eventId }) { result ->
                            SearchResultRow(
                                result = result,
                                roomName = roomName(result.roomId) ?: result.roomId,
                                now = now,
                                onClick = {
                                    onOpen(result.roomId)
                                    onClose()
                                },
                            )
                        }
                    }
            }
        }
    }
}

/** What the empty/idle state promises, which has to match what a search will actually do. */
private fun searchingWhere(scope: SearchPanelScope?, narrowed: Boolean): String {
    if (scope == null || !narrowed) return "Find a message across your rooms."
    return "Find a message in ${scope.name}."
}

@Composable
private fun StatusMessage(title: String, detail: String, testTag: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag(testTag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .testTag(testTag)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .wrapContentSize(),
    ) {
        Text(label, color = foreground, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * One hit: which room, when, and what it said — never which one is "more
 * relevant", a decision this row has no say in.
 */
@Composable
private fun SearchResultRow(result: SearchResultDto, roomName: String, now: Instant, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("search-result-${result.eventId}")
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                roomName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
            )
            Text(
                RelativeTime.label(result.timestampMs, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(result.body, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
