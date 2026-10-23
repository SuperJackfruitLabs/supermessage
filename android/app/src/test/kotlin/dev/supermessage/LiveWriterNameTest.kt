package dev.supermessage

import dev.supermessage.previews.PreviewFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.supermessage_core.Counterpart
import uniffi.supermessage_core.PersonDto
import uniffi.supermessage_core.RoomCast
import uniffi.supermessage_core.TimelineRow as TimelineRowDto

/**
 * Who the activity card is named for (D11, owner answer Q1): the turn's own
 * agent where the room's cast knows it, else the room's agent, else the last
 * speaker, else "Agent".
 */
class LiveWriterNameTest {
    private fun peer(name: String): TimelineRowDto = PreviewFixtures.message.copy(senderName = name)

    private fun own(): TimelineRowDto =
        PreviewFixtures.message.let { it.copy(item = it.item.copy(isOwn = true), senderName = "Me") }

    private fun person(id: String, name: String) =
        PersonDto(userId = id, name = name, initial = name.take(1), runtime = null, avatarUrl = null)

    private fun cast(counterpart: Counterpart?, vararg people: PersonDto) = RoomCast(
        roomId = "!r:x", people = people.toList(), counterpart = counterpart,
        agentIds = people.map { it.userId }, addressee = null, mentionables = emptyList(),
    )

    @Test
    fun theRoomsAgentNamesTheCardAndTheLastSpeakerDoesNot() {
        val rows = listOf(peer("atlas-bot (claude @ ci)"))
        assertEquals("Atlas", liveWriterName("Atlas", rows))
    }

    @Test
    fun withNoAgentTheLastPeerNamesIt() {
        assertEquals("atlas-bot (claude @ ci)", liveWriterName(null, listOf(peer("atlas-bot (claude @ ci)"), own())))
    }

    @Test
    fun withNobodyItIsAgent() {
        assertEquals("Agent", liveWriterName(null, emptyList()))
        assertEquals("Agent", liveWriterName(null, listOf(own())))
    }

    @Test
    fun theTurnsOwnAgentIsNamedWhenTheCastKnowsIt() {
        val atlas = person("@atlas:x", "Atlas")
        val vega = person("@vega:x", "Vega")
        val room = cast(counterpart = null, atlas, vega)
        assertEquals("Vega", liveWriterName(agentName = "Atlas", rows = emptyList(), agentId = "@vega:x", cast = room))
    }

    @Test
    fun theCounterpartIsNamedByTheCastsDisplayName() {
        val room = cast(Counterpart("@atlas:x", "Atlas"), person("@atlas:x", "Atlas (raw)"))
        assertEquals("Atlas", liveWriterName(agentName = "Other", rows = emptyList(), agentId = "@atlas:x", cast = room))
    }

    @Test
    fun anAgentTheCastDoesNotKnowFallsBackToTheRoomsAgent() {
        val room = cast(Counterpart("@atlas:x", "Atlas"), person("@atlas:x", "Atlas"))
        assertEquals("Atlas", liveWriterName("Atlas", listOf(peer("someone")), agentId = "@stranger:x", cast = room))
    }

    @Test
    fun aTurnWithNoAgentIdKeepsTheRoomsRule() {
        val room = cast(null, person("@vega:x", "Vega"))
        assertEquals("Atlas", liveWriterName("Atlas", emptyList(), agentId = null, cast = room))
    }

    @Test
    fun theRecordRidesTheNewestPeerBubble() {
        val a = peer("a"); val mine = own()
        val newest = PreviewFixtures.message.let { it.copy(item = it.item.copy(id = "newest")) }
        assertEquals("newest", recordAnchorId(listOf(a, mine, newest)))
        assertEquals("a newer row that is not a bubble does not take it", "newest", recordAnchorId(listOf(a, mine, newest, PreviewFixtures.dayDivider)))
        assertEquals(null, recordAnchorId(listOf(mine)))
        assertEquals(null, recordAnchorId(emptyList()))
    }
}
