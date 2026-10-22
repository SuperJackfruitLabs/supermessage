package dev.supermessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The header's count line, as iOS words it (`RoomScreen.swift:212`). */
class RoomHeaderLabelTest {
    @Test
    fun noCountSaysNothing() = assertNull(memberCountLabel(null))

    @Test
    fun zeroSaysNothing() = assertNull(memberCountLabel(0uL))

    @Test
    fun oneIsSingular() = assertEquals("1 member", memberCountLabel(1uL))

    @Test
    fun manyIsPlural() = assertEquals("4 members", memberCountLabel(4uL))
}
