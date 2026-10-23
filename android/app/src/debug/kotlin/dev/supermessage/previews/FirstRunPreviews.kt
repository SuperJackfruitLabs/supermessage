package dev.supermessage.previews

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import dev.supermessage.AppearanceMode
import dev.supermessage.FirstRunDemoContent
import dev.supermessage.LocalReduceMotion
import dev.supermessage.SupermessageTheme
import dev.supermessage.ThemeChoice
import dev.supermessage.WelcomeScreen
import dev.supermessage.kit.FirstRunDemo
import uniffi.supermessage_core.FirstRunStep

/**
 * The welcome (`WelcomeView.swift:86-96`) and the first-run demo (`FirstRunDemoView.swift:150-160`),
 * each still at a fixed step. Reduce Motion is provided on, so nothing here animates for ever: the
 * mark does not settle, the typing dots do not step, and the arrivals are fades.
 */
@Composable
private fun Still(dark: Boolean, content: @Composable () -> Unit) {
    SupermessageTheme(choice = ThemeChoice(mode = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT)) {
        CompositionLocalProvider(
            LocalReduceMotion provides true,
            LocalContentColor provides SupermessageTheme.colors.content,
        ) { content() }
    }
}

@Composable
private fun Demo(step: FirstRunStep, dark: Boolean = false) =
    Still(dark) { FirstRunDemoContent(demo = FirstRunDemo(), step = step, onFinish = {}) }

@Preview(name = "Welcome", showBackground = true, widthDp = 411, heightDp = 800)
@Composable
internal fun Welcome() = Still(dark = false) { WelcomeScreen(onSignIn = {}) }

@Preview(name = "Welcome, dark", showBackground = true, widthDp = 411, heightDp = 800)
@Composable
internal fun WelcomeDark() = Still(dark = true) { WelcomeScreen(onSignIn = {}) }

@Preview(name = "First run, Atlas typing", showBackground = true, widthDp = 411, heightDp = 640)
@Composable
internal fun FirstRunDemoGreeting() = Demo(FirstRunStep.GREETING)

@Preview(name = "First run, the card", showBackground = true, widthDp = 411, heightDp = 800)
@Composable
internal fun FirstRunDemoWaiting() = Demo(FirstRunStep.WAITING)

@Preview(name = "First run, approved", showBackground = true, widthDp = 411, heightDp = 900)
@Composable
internal fun FirstRunDemoApproved() = Demo(FirstRunStep.APPROVED)

@Preview(name = "First run, dark", showBackground = true, widthDp = 411, heightDp = 800)
@Composable
internal fun FirstRunDemoDark() = Demo(FirstRunStep.WAITING, dark = true)

@Preview(name = "First run, 2x", showBackground = true, widthDp = 411, heightDp = 1000, fontScale = 2f)
@Composable
internal fun FirstRunDemoLargeText() = Demo(FirstRunStep.WAITING)
