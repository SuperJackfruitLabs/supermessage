package dev.supermessage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** True while the mark is still settling to full size; false once it has, and always under Reduce Motion. */
internal val Arriving = SemanticsPropertyKey<Boolean>("Arriving")
private var SemanticsPropertyReceiver.arriving by Arriving

/**
 * What a signed-out install shows: the welcome until it has been passed, the sign-in form after it
 * (`WelcomeView.swift:19-32`). Passing it is remembered per install ([FirstRunPreferences.welcomeSeen]).
 *
 * Nothing is drawn until the stored answer has been read, so a returning reader never sees the welcome
 * flash before the form.
 */
@Composable
internal fun SignedOutContent(
    firstRun: FirstRunPreferences?,
    signIn: @Composable () -> Unit,
) {
    // No preferences, no onboarding: straight to the form (a test that is not about the welcome).
    if (firstRun == null) {
        signIn()
        return
    }
    val welcomeSeen by firstRun.welcomeSeen.collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    val scope = rememberCoroutineScope()
    when (welcomeSeen) {
        null -> {}
        false -> WelcomeScreen(onSignIn = { scope.launch { firstRun.setWelcomeSeen(true) } })
        true -> signIn()
    }
}

/**
 * The welcome (`WelcomeView.swift:35-83`): the mark, the name, one line of what this is, and "Sign in".
 * Scrolls when the screen is too short for it (the short height and large text).
 */
@Composable
fun WelcomeScreen(onSignIn: () -> Unit) {
    val colors = SupermessageTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.surface).testTag("welcome")) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewport)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Column(
                    Modifier.padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    WelcomeMark()
                    Text(
                        "supermessage",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.content,
                        modifier = Modifier.padding(top = 20.dp).semantics { heading() },
                    )
                    Text(
                        "Chat with your team and your agents — and approve their work without leaving the conversation.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.contentMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp, start = 8.dp, end = 8.dp).testTag("welcome-tagline"),
                    )
                }
                Button(
                    onClick = onSignIn,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.accentContent),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp).heightIn(min = 52.dp).testTag("welcome-sign-in"),
                ) {
                    Text("Sign in", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * The mark at 132 wide, scaling 0.94 → 1 on a 0.6 s spring with bounce 0.3 as it appears. Scale only,
 * never opacity (`WelcomeView.swift:41-46`); no animation under Reduce Motion.
 */
@Composable
private fun WelcomeMark() {
    val reduce = reducesMotion()
    val scale = remember(reduce) { Animatable(if (reduce) 1f else ArrivalFrom) }
    LaunchedEffect(reduce) {
        if (!reduce) scale.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 110f))
    }
    Image(
        painter = painterResource(R.drawable.mark),
        contentDescription = "supermessage",
        modifier = Modifier
            .size(width = 132.dp, height = 132.dp * 344 / 390)
            .testTag("welcome-mark")
            .semantics { arriving = !reduce && (scale.isRunning || scale.value != 1f) }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    )
}

private const val ArrivalFrom = 0.94f
