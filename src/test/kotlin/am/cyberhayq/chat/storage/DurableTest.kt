package am.cyberhayq.chat.storage

import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The order of steps in `durableWrite`; the real system calls run only on a device. */
class DurableTest {
    @get:Rule val temporary = TemporaryFolder()
    private val durable = JvmDurable.install()

    @After fun restore() = JvmDurable.uninstall()

    @Test fun durableWriteSyncsRenamesThenSyncsTheFolder() {
        val file = File(temporary.root, "selector")
        Durable.durableWrite(file, byteArrayOf(1, 2, 3))
        Durable.durableWrite(file, byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), file.readBytes())
        assertEquals(listOf("selector"), temporary.root.list()!!.toList())
        assertEquals(listOf("sync ", "rename selector", "fsync " + temporary.root.name), durable.steps.take(3))
    }

    @Test fun aCrashBeforeTheRenameKeepsTheOldContent() {
        val file = File(temporary.root, "selector")
        Durable.durableWrite(file, byteArrayOf(1))
        durable.stopBefore = "rename"
        try { Durable.durableWrite(file, byteArrayOf(2)); fail() } catch (e: IOException) { }
        assertArrayEquals(byteArrayOf(1), file.readBytes())
    }

    @Test fun anErrorAfterTheRenameLeavesTheNewContent() {
        val file = File(temporary.root, "selector")
        Durable.durableWrite(file, byteArrayOf(1))
        durable.stopBefore = "fsync"
        try { Durable.durableWrite(file, byteArrayOf(2)); fail() } catch (e: IOException) { }
        assertArrayEquals("the caller must look at the file, not assume the old content", byteArrayOf(2), file.readBytes())
    }

    @Test fun syncFileDoesNotCreateAMissingFile() {
        val missing = File(temporary.root, "missing")
        try { Durable.syncFile(missing); fail() } catch (e: IOException) { }
        assertFalse(missing.exists())
    }
}
