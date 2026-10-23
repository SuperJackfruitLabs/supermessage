package dev.supermessage.kit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.supermessage_core.FirstRunScript
import uniffi.supermessage_core.FirstRunStep
import uniffi.supermessage_ffi.firstRunAdvance
import uniffi.supermessage_ffi.firstRunAnswer
import uniffi.supermessage_ffi.firstRunScript

/**
 * The first-run demo's state: which step it is on, and what the reader answered (iOS
 * `FirstRunDemo.swift:15-39`). Every word, pause and rule is the core's (`first_run.rs`); this is the
 * host's holder for them.
 *
 * **It has no client.** No `CoreClient`, no `Session`, no `CoreInterface`: the constructor takes
 * nothing, so there is nothing to send through. The demo never touches the homeserver, and the
 * signature is how that is kept true (`FirstRunDemoTest.theDemoHasNoClient`).
 */
class FirstRunDemo {
    val script: FirstRunScript = firstRunScript()

    private val _step = MutableStateFlow(FirstRunStep.GREETING)
    val step: StateFlow<FirstRunStep> = _step.asStateFlow()

    private val _answer = MutableStateFlow<String?>(null)
    /** The option the reader chose; `null` until they have. */
    val answer: StateFlow<String?> = _answer.asStateFlow()

    /** The pace's step. The core stops it at the card: only [answer] gets past that. */
    fun advance() {
        _step.value = firstRunAdvance(_step.value)
    }

    /** `false` when the core takes no answer at this step (typing, asking, or already answered). */
    fun answer(optionId: String): Boolean {
        val next = firstRunAnswer(_step.value) ?: return false
        _answer.value = optionId
        _step.value = next
        return true
    }
}
