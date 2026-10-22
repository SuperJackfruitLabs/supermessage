package dev.supermessage.kit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.supermessage_core.Mentionable
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomCast

@OptIn(ExperimentalCoroutinesApi::class)
class MentionCastTest {
    private fun person(id: String, name: String = id.removePrefix("@").substringBefore(':')) =
        PersonDto(id, name.replaceFirstChar { it.uppercase() }, name.take(1).uppercase(), null, null)

    private val atlas = person("@atlas:x")
    private val priya = person("@priya:x")

    private fun castOf(roomId: String, vararg people: PersonDto) = RoomCast(
        roomId = roomId,
        people = people.toList(),
        counterpart = null,
        agentIds = emptyList(),
        addressee = null,
        mentionables = people.map { Mentionable(it.userId, it.name) },
    )

    @Test
    fun loadAsksForTheGivenRoom() = runTest {
        val cast = MentionCast()
        val asked = mutableListOf<String>()
        cast.load("!b") { id -> asked += id; castOf(id, priya) }
        assertEquals(listOf("!b"), asked)
        assertEquals(listOf(priya), cast.people)
    }

    @Test
    fun theCastReloadsForTheNewRoom() = runTest {
        val cast = MentionCast()
        val fetch: suspend (String) -> RoomCast? = { id -> if (id == "!a") castOf(id, atlas) else castOf(id, priya) }
        cast.load("!a", fetch)
        assertEquals(listOf(atlas), cast.people)
        cast.load("!b", fetch)
        assertEquals(listOf(priya), cast.people)
        assertEquals("@priya:x", cast.mentionables.single().userId)
    }

    @Test
    fun aResultArrivingAfterARoomSwitchIsDropped() = runTest {
        val cast = MentionCast()
        val slow = CompletableDeferred<RoomCast?>()
        launch { cast.load("!a") { slow.await() } }
        advanceUntilIdle()
        cast.load("!b") { castOf(it, priya) }
        slow.complete(castOf("!a", atlas))
        advanceUntilIdle()
        assertEquals(listOf(priya), cast.people)
    }

    @Test
    fun theLastRoomsPeopleAreGoneWhileTheNewRoomLoads() = runTest {
        val cast = MentionCast()
        cast.load("!a") { castOf(it, atlas) }
        val slow = CompletableDeferred<RoomCast?>()
        launch { cast.load("!b") { slow.await() } }
        advanceUntilIdle()
        assertTrue(cast.people.isEmpty())
        slow.complete(castOf("!b", priya))
        advanceUntilIdle()
        assertEquals(listOf(priya), cast.people)
    }

    @Test
    fun aCastTheCoreCouldNotGiveIsEmpty() = runTest {
        val cast = MentionCast()
        cast.load("!a") { null }
        assertTrue(cast.people.isEmpty())
        assertTrue(cast.mentionables.isEmpty())
    }

    @Test
    fun candidatesAreCappedAtSix() = runTest {
        val cast = MentionCast()
        val many = (1..8).map { person("@p$it:x", "p$it") }
        cast.load("!r") { castOf(it, *many.toTypedArray()) }
        assertEquals(6, cast.candidates("").size)
    }

    @Test
    fun mentionablesAreTheCores() = runTest {
        val cast = MentionCast()
        cast.load("!r") { castOf(it, atlas) }
        assertEquals("Atlas", cast.mentionables.single().displayName)
        assertEquals("@atlas:x", cast.mentionables.single().userId)
    }
}
