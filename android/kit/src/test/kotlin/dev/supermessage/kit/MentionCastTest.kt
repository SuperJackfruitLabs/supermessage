package dev.supermessage.kit

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.supermessage_core.PersonDto

class MentionCastTest {
    private fun person(id: String, name: String = id.removePrefix("@").substringBefore(':')) =
        PersonDto(id, name.replaceFirstChar { it.uppercase() }, name.take(1).uppercase(), null, null)

    private val atlas = person("@atlas:x")
    private val priya = person("@priya:x")
    private val kai = person("@kai:x")

    @Test
    fun membersFilterKnownPeople() = runTest {
        val cast = MentionCast()
        cast.load("!r", { listOf(atlas, priya, kai) }, { listOf("@atlas:x", "@kai:x") })
        assertEquals(listOf(atlas, kai), cast.people.value)
    }

    @Test
    fun unreadableMembersFallBackToEveryone() = runTest {
        val cast = MentionCast()
        cast.load("!r", { listOf(atlas, priya, kai) }, { null })
        assertEquals(listOf(atlas, priya, kai), cast.people.value)
    }

    @Test
    fun candidatesAreCappedAtSix() = runTest {
        val cast = MentionCast()
        val many = (1..8).map { person("@p$it:x", "p$it") }
        cast.load("!r", { many }, { null })
        assertEquals(6, cast.candidates("").size)
    }

    @Test
    fun mentionablesAreLabelledAsThePickerInserts() = runTest {
        val cast = MentionCast()
        cast.load("!r", { listOf(atlas) }, { null })
        assertEquals("Atlas", cast.mentionables.single().displayName)
        assertEquals("@atlas:x", cast.mentionables.single().userId)
    }
}
