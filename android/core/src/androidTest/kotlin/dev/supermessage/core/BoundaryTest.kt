package dev.supermessage.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.supermessage_ffi.peopleLabel

/**
 * The whole chain in one call: Gradle packaged the .so, JNA found it by the
 * name the generated Kotlin asks for, and Rust answered.
 *
 * If this fails with UnsatisfiedLinkError the libraries are missing or the
 * ABI is wrong. If it fails on the assertion, the boundary drifted and the
 * bindings were not regenerated.
 *
 * The expected strings below come from
 * `supermessage_core::display_name::people_label` and the `user_label` /
 * `humanise` helpers it delegates to (crates/supermessage-core/src/display_name.rs):
 * the localpart of a Matrix id is title-cased word by word (`ganesha` ->
 * "Ganesha"), and two people are joined with a bare "and" and no comma.
 */
@RunWith(AndroidJUnit4::class)
class BoundaryTest {

    @Test
    fun theCoreAnswersFromTheOtherSideOfTheBoundary() {
        val label = peopleLabel(listOf("@ganesha:supermessage.dev"))
        assertEquals("Ganesha", label)
    }

    @Test
    fun twoPeopleAreNamedTogether() {
        val label = peopleLabel(
            listOf("@ganesha:supermessage.dev", "@rakesh:supermessage.dev"))
        assertEquals("Ganesha and Rakesh", label)
    }
}
