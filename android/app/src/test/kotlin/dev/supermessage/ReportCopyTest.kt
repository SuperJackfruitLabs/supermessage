package dev.supermessage

import dev.supermessage.kit.ReportSubject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every string of the report sheet, against `ReportSheet.swift:171-228`. */
class ReportCopyTest {
    private val message = ReportSubject.Message("!r", "\$e", "@s:x", "Sam", isAgent = false)
    private val room = ReportSubject.Room("!r", "Lounge")
    private val person = ReportSubject.User("@p:x", "Pat", isAgent = false)
    private val agent = ReportSubject.User("@agent_x:x", "Atlas", isAgent = true)

    @Test
    fun titles() {
        assertEquals("Report message", ReportCopy.title(message))
        assertEquals("Report room", ReportCopy.title(room))
        assertEquals("Report", ReportCopy.title(person))
    }

    @Test
    fun explanations() {
        assertEquals(
            "Your report goes to your homeserver's administrators, not to Sam. " +
                "The message is hidden from you once the report is sent.",
            ReportCopy.explanation(message),
        )
        assertEquals(
            "Your report about Lounge goes to your homeserver's administrators, not to the people in it.",
            ReportCopy.explanation(room),
        )
        assertEquals(
            "Your report goes to your homeserver's administrators, not to Pat.",
            ReportCopy.explanation(person),
        )
    }

    @Test
    fun anAgentReportSaysItKeepsRunningAndAPersonOneDoesNot() {
        assertEquals(
            "Your report goes to your homeserver's administrators, not to Atlas. " +
                "Reporting an agent doesn't stop it running.",
            ReportCopy.explanation(agent),
        )
    }

    @Test
    fun confirmations() {
        assertEquals("Report this message?", ReportCopy.confirmationTitle(message))
        assertEquals("Report Lounge?", ReportCopy.confirmationTitle(room))
        assertEquals("Report Pat?", ReportCopy.confirmationTitle(person))
        assertEquals(
            "The homeserver's administrators will see it. Sam won't be told.",
            ReportCopy.confirmationMessage(message),
        )
        assertEquals(
            "The homeserver's administrators will see it. Lounge won't be told.",
            ReportCopy.confirmationMessage(room),
        )
        assertEquals("Sam", ReportCopy.subjectName(message))
        assertEquals("Lounge", ReportCopy.subjectName(room))
        assertEquals("Pat", ReportCopy.subjectName(person))
    }

    @Test
    fun sendLabels() {
        assertEquals("Report and block", ReportCopy.sendLabel(message, alsoBlock = true))
        assertEquals("Report", ReportCopy.sendLabel(message, alsoBlock = false))
        assertEquals("Report and block", ReportCopy.sendLabel(person, alsoBlock = true))
        assertEquals("Report", ReportCopy.sendLabel(room, alsoBlock = true))
    }

    @Test
    fun blockWords() {
        assertEquals(
            "Its messages are hidden everywhere, on all your devices. The agent keeps running and isn't told.",
            ReportCopy.blockConsequence(isAgent = true),
        )
        assertEquals(
            "You won't see their messages in any room, on any of your devices. They aren't told.",
            ReportCopy.blockConsequence(isAgent = false),
        )
        assertEquals("Pat is blocked", ReportCopy.alreadyBlocked("Pat"))
        assertEquals("Also block Pat", ReportCopy.alsoBlock("Pat"))
    }

    @Test
    fun theFormWords() {
        assertEquals("Reason", ReportCopy.REASON_HEADER)
        assertEquals("What's wrong? (optional)", ReportCopy.REASON_PLACEHOLDER)
        assertEquals("Reason for the report", ReportCopy.REASON_LABEL)
        assertEquals("Optional. Seen only by the homeserver's administrators.", ReportCopy.REASON_HINT)
        assertEquals("Asks before sending", ReportCopy.SEND_HINT)
    }

    @Test
    fun theCounterAppearsOnlyNearTheLimit() {
        assertNull(ReportCopy.counter(1500))
        assertNull(ReportCopy.counter(200))
        assertEquals("199 characters left" to false, ReportCopy.counter(199))
        assertEquals("0 characters left" to false, ReportCopy.counter(0))
        assertEquals("1 characters over the limit" to true, ReportCopy.counter(-1))
        assertEquals("5 characters over the limit" to true, ReportCopy.counter(-5))
    }
}
