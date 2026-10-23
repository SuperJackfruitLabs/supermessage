package dev.supermessage

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pull to refresh over a list (`RoomListView.swift:175-178`, `NeedsYouView.swift:53-56`,
 * `AgentsView.swift:52-55`): [onRefresh] runs, and the indicator stays until it returns.
 * A second pull while one is out does nothing.
 *
 * The indicator is tagged `refresh-indicator` only while it is refreshing, so a test can
 * read whether it is up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Refreshable(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    try {
                        onRefresh()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A read that failed leaves the list as it was; the connection line says why.
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(if (refreshing) Modifier.testTag("refresh-indicator") else Modifier),
                containerColor = SupermessageTheme.colors.surfaceRaised,
                color = SupermessageTheme.colors.accent,
            )
        },
    ) {
        content()
    }
}
