package dev.supermessage.widgets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileSnapshotStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test(timeout = 30_000)
    fun aMissingFileReadsAsNothing() {
        assertNull(FileSnapshotStore.inside(folder.root).read())
    }

    @Test(timeout = 30_000)
    fun aWriteReplacesTheFileWhole() {
        val store = FileSnapshotStore.inside(folder.root)
        store.write("""{"schema":3,"first":"a much longer first document"}""")
        store.write("""{"schema":3}""")
        assertEquals("""{"schema":3}""", store.read())
        val directory = File(folder.root, "widgets")
        assertEquals(listOf("snapshot.json"), directory.list()!!.toList())
        assertFalse(File(directory, "snapshot.json.tmp").exists())
    }
}
