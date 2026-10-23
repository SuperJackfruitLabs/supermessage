package dev.supermessage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-run demo sends nothing and touches no account. `FirstRunTest` proves it against a
 * recording core; this proves the demo's sources cannot reach one at all: no Session, no store,
 * no client, no send, no network. A new import is a compile-time reach the recording core might
 * not exercise.
 */
class FirstRunSourceGuardTest {
    private val sources = listOf(
        "src/main/kotlin/dev/supermessage/FirstRunDemo.kt",
        "../kit/src/main/kotlin/dev/supermessage/kit/FirstRunDemo.kt",
    )

    private val forbiddenImports = listOf(
        Regex("""^import dev\.supermessage\.kit\.(Session|CoreClient|SendState|SendResult|EventPump|VoicePlayback)\b"""),
        Regex("""^import dev\.supermessage\.kit\.stores\."""),
        Regex("""^import uniffi\.supermessage_ffi\.(Core|CoreInterface|EventSink)\b"""),
        Regex("""^import (java\.net|okhttp3|android\.net)\."""),
        // A wildcard would bring every name above in at once.
        Regex("""^import (dev\.supermessage\.kit|dev\.supermessage\.kit\.stores|uniffi\.supermessage_ffi)\.\*"""),
    )

    @Test
    fun theDemoImportsNothingThatSendsOrHoldsAnAccount() {
        sources.forEach { path ->
            val file = File(path)
            assertTrue("missing ${file.absolutePath}", file.exists())
            file.readLines().filter { it.startsWith("import ") }.forEach { line ->
                forbiddenImports.forEach { rule ->
                    assertTrue("$path imports ${line.removePrefix("import ")}", !rule.containsMatchIn(line))
                }
            }
        }
    }

    @Test
    fun theDemoNamesNoSessionOrSendCall() {
        sources.forEach { path ->
            val code = File(path).readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            code.forEach { line ->
                assertTrue("$path calls into a session or send: $line", !Regex("""\bsession\.|\bsendMessage\b|\.send\(""").containsMatchIn(line))
            }
        }
    }
}
