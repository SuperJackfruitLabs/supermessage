package dev.supermessage.widgets

import dev.supermessage.kit.widgets.SnapshotStore
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * The widgets' snapshot as `filesDir/widgets/snapshot.json`: one file,
 * replaced whole (`WidgetSnapshot.swift:38-85`). A write goes to
 * `snapshot.json.tmp` and is renamed over the real one, which replaces it in
 * one step, so a widget reading with no lock sees the old snapshot or the new
 * one and never half of either. Writers are serialised above this, by
 * `WidgetFeed`'s lock.
 */
class FileSnapshotStore(private val file: File) : SnapshotStore {
    private val temporary = File(file.parentFile, file.name + ".tmp")

    override fun read(): String? = try {
        file.readText()
    } catch (_: FileNotFoundException) {
        null
    }

    override fun write(json: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(temporary).use { out ->
            out.write(json.toByteArray())
            // On disk before it becomes the snapshot.
            out.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("could not replace $file")
    }

    companion object {
        /** `filesDir/widgets/snapshot.json`. */
        fun inside(filesDir: File): FileSnapshotStore = FileSnapshotStore(File(File(filesDir, "widgets"), "snapshot.json"))
    }
}
