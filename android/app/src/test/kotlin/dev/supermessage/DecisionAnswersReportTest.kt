package dev.supermessage

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.supermessage_core.CustomEventDecisionOption

/** The widgets stop offering a decision once an answer from its card has landed, and not before. */
@OptIn(ExperimentalCoroutinesApi::class)
class DecisionAnswersReportTest {
    private val haptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
    }
    private val approve = CustomEventDecisionOption(label = "Approve", id = "approve")
    private val answer = GateAnswer(subject = null, optionId = "approve", comment = null, prompt = "Ship v2?")

    @Test(timeout = 30_000)
    fun aLandedAnswerIsReportedOnce() = runTest(UnconfinedTestDispatcher()) {
        val told = mutableListOf<Pair<String, String>>()
        val answers = DecisionAnswers(backgroundScope, onAnswered = { key, option -> told += key to option })
        answers.send("\$e1", approve, answer, handler = { true }, haptics = haptics, onLanded = {})
        answers.send("\$e1", approve, answer, handler = { true }, haptics = haptics, onLanded = {})
        assertEquals(listOf("\$e1" to "approve"), told)
    }

    @Test(timeout = 30_000)
    fun anAnswerThatFailedIsNotReported() = runTest(UnconfinedTestDispatcher()) {
        val told = mutableListOf<Pair<String, String>>()
        val answers = DecisionAnswers(backgroundScope, onAnswered = { key, option -> told += key to option })
        answers.send("\$e1", approve, answer, handler = { false }, haptics = haptics, onLanded = {})
        assertEquals(emptyList<Pair<String, String>>(), told)
    }
}
