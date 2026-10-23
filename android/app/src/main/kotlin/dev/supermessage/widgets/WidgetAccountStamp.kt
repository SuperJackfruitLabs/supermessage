package dev.supermessage.widgets

import java.io.File
import java.io.IOException

/**
 * Whose decisions the widgets' snapshot holds: the signed-in account's Matrix id, written
 * next to the snapshot while the app is signed in and removed on sign-out.
 *
 * The snapshot itself has no account (it is the core's, and single-account). A widget's
 * button reads this to refuse an answer whose session is another account's, as M6's
 * notification answers do. **No stamp is no match**: nothing is sent, and the reader
 * opens the app (which writes it again) to answer there.
 */
class WidgetAccountStamp(private val file: File) {
    fun read(): String? = try {
        file.readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: IOException) {
        null
    }

    fun write(userId: String) {
        file.parentFile?.mkdirs()
        file.writeText(userId)
    }

    fun clear() {
        file.delete()
    }

    companion object {
        fun inside(filesDir: File) = WidgetAccountStamp(File(File(filesDir, "widgets"), "account"))
    }
}
