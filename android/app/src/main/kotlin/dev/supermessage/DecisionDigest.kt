package dev.supermessage

import java.time.Duration
import java.time.Instant
import uniffi.supermessage_core.CustomEventDecisionOption
import uniffi.supermessage_core.CustomEventField

/**
 * The leading fields of a decision card, folded into one line, and the rest
 * for the "Details" disclosure. `DecisionCard.swift:443-476`.
 *
 * Presentation only: which fields exist, their order and their labels are the
 * renderer's. The renderers lead with the field that names the thing, so the
 * summary is the leading fields' values, skipping any the prompt already says
 * word for word (a permission request's prompt is `Allow {title}?`, and
 * repeating the title beneath it is noise). Nothing is dropped that is not
 * already on screen.
 */
internal data class FieldDigest(val summary: String?, val rest: List<CustomEventField>)

internal fun fieldDigest(fields: List<CustomEventField>, prompt: String): FieldDigest {
    fun saidAlready(field: CustomEventField) = field.value.isNotEmpty() && prompt.contains(field.value)
    val candidates = fields.withIndex().filterNot { saidAlready(it.value) }
    val used = mutableListOf<Int>()
    val first = candidates.firstOrNull()
    if (first != null) {
        used += first.index
        // The second only when short enough that the pair still reads as one
        // line on a phone.
        val second = candidates.getOrNull(1)
        if (second != null && second.value.value.length <= 32 && first.value.value.length <= 40) {
            used += second.index
        }
    }
    return FieldDigest(
        summary = used.takeIf { it.isNotEmpty() }?.joinToString(" · ") { fields[it].value },
        rest = fields.withIndex().filter { it.index !in used && !saidAlready(it.value) }.map { it.value },
    )
}

/**
 * Which part an option plays on the card. Identified by **id**, never by
 * label. `reject` is matched without regard to case because a permission
 * request's ids are the hub's option names ("Reject"). Anything unrecognised is
 * [OTHER] and drawn as an ordinary secondary answer.
 */
internal enum class DecisionOptionRole {
    APPROVE, REQUEST_CHANGES, REJECT, OTHER;

    companion object {
        fun of(option: CustomEventDecisionOption): DecisionOptionRole =
            when (option.id.lowercase()) {
                "approve" -> APPROVE
                GateAnswer.REQUEST_CHANGES -> REQUEST_CHANGES
                "reject" -> REJECT
                else -> OTHER
            }
    }
}

/**
 * The three gate answers are Superpipeline's fixed vocabulary, so they can be
 * said in the past tense. Anything else is quoted back in the label the
 * renderer chose: inventing a past tense for a verb we do not know is how a
 * receipt ends up saying something the reader did not do.
 */
internal fun receiptOutcome(option: CustomEventDecisionOption): String =
    when (DecisionOptionRole.of(option)) {
        DecisionOptionRole.APPROVE -> "Approved by you"
        DecisionOptionRole.REQUEST_CHANGES -> "Changes requested by you"
        DecisionOptionRole.REJECT -> "Rejected by you"
        DecisionOptionRole.OTHER -> "You chose “${option.label}”"
    }

/** "just now" for the first minute, then the largest whole unit ("2 minutes ago"). */
internal fun receiptWhen(answeredAt: Instant, now: Instant): String {
    val seconds = Duration.between(answeredAt, now).seconds
    if (seconds < 60) return "just now"
    fun unit(n: Long, name: String) = "$n $name${if (n == 1L) "" else "s"} ago"
    return when {
        seconds < 3_600 -> unit(seconds / 60, "minute")
        seconds < 86_400 -> unit(seconds / 3_600, "hour")
        else -> unit(seconds / 86_400, "day")
    }
}
