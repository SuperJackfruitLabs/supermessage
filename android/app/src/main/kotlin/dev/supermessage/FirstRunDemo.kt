package dev.supermessage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermessage.kit.FirstRunDemo
import uniffi.supermessage_core.AgentState
import uniffi.supermessage_core.FirstRunStep
import uniffi.supermessage_ffi.agentAvatarStyle

/**
 * The first-run demo (`Onboarding/FirstRunDemoView.swift:17-147`): a sample conversation with an agent
 * that asks for a permission, so the reader answers one before they ever meet a real one.
 *
 * **It takes no session.** Nothing here can reach the homeserver: the signature is the promise, as on
 * iOS (`:8-13`). Every word and every pause is the core's ([FirstRunDemo.script]); the card is the real
 * [DecisionCard], handed a local `onDecide` that only calls [FirstRunDemo.answer].
 *
 * A full-screen dialog (iOS `fullScreenCover`) over the signed-in shell, left only by Skip or Get
 * started; the system's back is Skip. [delay] is injectable so a test can read the pace.
 */
@Composable
fun FirstRunDemoScreen(
    onFinish: () -> Unit,
    demo: FirstRunDemo = remember { FirstRunDemo() },
    delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    val script = demo.script
    val step by demo.step.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    // The pace is reading time, not animation: it holds under Reduce Motion (`:94-101`). It stops at
    // the card, because the core stops advancing there.
    LaunchedEffect(demo) {
        for (d in script.stepDelaysMs) {
            delay(d.toLong())
            demo.advance()
        }
    }
    LaunchedEffect(step) {
        if (step == FirstRunStep.APPROVED) haptic.play(Haptic.Success)
    }

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        FirstRunDemoContent(demo, step, onFinish)
    }
}

@Composable
internal fun FirstRunDemoContent(demo: FirstRunDemo, step: FirstRunStep, onFinish: () -> Unit) {
    val colors = SupermessageTheme.colors
    val script = demo.script
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .systemBarsPadding()
            .testTag("first-run"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                script.agentName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.content,
                modifier = Modifier.weight(1f),
            )
            if (step != FirstRunStep.APPROVED) {
                TextButton(onClick = onFinish, modifier = Modifier.testTag("first-run-skip")) {
                    Text(script.skipLabel, color = colors.accent)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    script.banner,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.contentFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("first-run-banner"),
                )
                if (step == FirstRunStep.GREETING) {
                    Arrives(key = "typing") { TypingBubble(demo) }
                } else {
                    Arrives(key = "message") { AgentMessage(demo) }
                }
                if (step == FirstRunStep.WAITING || step == FirstRunStep.APPROVED) {
                    Arrives(key = "card") {
                        Box(Modifier.testTag("first-run-card")) {
                            DecisionCard(
                                view = script.card,
                                label = script.cardLabel,
                                eventType = script.cardEventType,
                                senderName = script.agentName,
                                onDecide = { answer -> demo.answer(answer.optionId) },
                            )
                        }
                    }
                }
                if (step == FirstRunStep.APPROVED) {
                    Arrives(key = "approved") { Approved(demo, onFinish) }
                }
            }
        }
    }
}

/**
 * A piece of the conversation arriving: up from the bottom with a fade on a spring (`:80`, `:104-106`);
 * under Reduce Motion a fade only.
 */
@Composable
private fun Arrives(key: String, content: @Composable () -> Unit) {
    val reduce = reducesMotion()
    val state = remember(key) { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = if (reduce) {
            fadeIn(tween(450))
        } else {
            slideInVertically(spring(dampingRatio = 0.8f, stiffness = 200f)) { it / 2 } +
                fadeIn(spring(dampingRatio = 0.8f, stiffness = 200f))
        },
    ) { content() }
}

@Composable
private fun AgentAvatar(demo: FirstRunDemo) {
    val script = demo.script
    val style = remember(script.roomId) { agentAvatarStyle(script.roomId) }
    RoomAvatar(
        roomId = script.roomId,
        initial = script.initial,
        avatarUri = null,
        describesAgent = true,
        style = style,
        state = AgentState.ACTIVE,
        size = 32.dp,
    )
}

/** Atlas is typing: its avatar, then three dots stepping in brightness (still under Reduce Motion). */
@Composable
private fun TypingBubble(demo: FirstRunDemo) {
    val colors = SupermessageTheme.colors
    val reduce = reducesMotion()
    Row(
        Modifier.testTag("first-run-typing"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        AgentAvatar(demo)
        val phase = if (reduce) 0f else {
            val p by rememberInfiniteTransition(label = "first-run-typing").animateFloat(
                initialValue = 0f,
                targetValue = 3f,
                animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
                label = "first-run-typing-phase",
            )
            p
        }
        Row(
            Modifier
                .background(colors.surfaceRaised, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = demo.script.typingLabel },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                val lit = reduce || phase.toInt() == i
                Box(
                    Modifier.size(7.dp).alpha(if (lit) 1f else 0.3f)
                        .background(colors.contentMuted, CircleShape),
                )
            }
        }
    }
}

/** From asking on: the avatar, then the name with its Agent tag over what Atlas said. */
@Composable
private fun AgentMessage(demo: FirstRunDemo) {
    val colors = SupermessageTheme.colors
    val script = demo.script
    Row(
        Modifier.fillMaxWidth().testTag("first-run-message"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AgentAvatar(demo)
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    script.agentName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.content,
                )
                Text(
                    script.agentTag,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier
                        .background(colors.accentSoft, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Text(
                script.greeting,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.content,
                modifier = Modifier
                    .background(colors.surfaceRaised, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            )
        }
    }
}

/** The receipt of the reader's answer: a check, what it means, and the way out. */
@Composable
private fun Approved(demo: FirstRunDemo, onFinish: () -> Unit) {
    val colors = SupermessageTheme.colors
    val script = demo.script
    val reduce = reducesMotion()
    // One bounce as it lands, hidden from accessibility.
    val bounce = remember { Animatable(if (reduce) 1f else 0.6f) }
    LaunchedEffect(reduce) {
        if (!reduce) bounce.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 300f))
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).testTag("first-run-approved"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = colors.ok,
            modifier = Modifier
                .size(40.dp)
                .graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }
                .clearAndSetSemantics {},
        )
        Text(
            script.approvedTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            textAlign = TextAlign.Center,
        )
        Text(
            script.approvedBody,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.contentMuted,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onFinish,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.accentContent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 4.dp).testTag("first-run-finish"),
        ) {
            Text(script.finishLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
