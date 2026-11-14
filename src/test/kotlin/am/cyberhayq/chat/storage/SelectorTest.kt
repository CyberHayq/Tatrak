package am.cyberhayq.chat.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class SelectorTest {
    @Test fun selectorRejectsTornAndChangedSelections() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val bytes = Selector.encode(first)
        assertEquals(first, Selector.decode(bytes))
        assertThrows(IllegalArgumentException::class.java) { Selector.decode(bytes.copyOf(70)) }
        val changed = String(bytes).replace(first, second).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { Selector.decode(changed) }
        assertThrows(IllegalArgumentException::class.java) { Selector.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { Selector.encode("../history") }
    }

    @Test fun mayNameDecidesWhetherActivationIsPastCommit() {
        val base = Files.createTempDirectory("selectors").toFile()
        val stage = UUID.randomUUID().toString()
        val old = UUID.randomUUID().toString()
        val plain: (File) -> Boolean = { !it.exists() }
        try {
            assertFalse(Selector.mayName(base, stage, plain))
            File(base, "active").writeBytes(Selector.encode(old))
            File(base, "commit.new").writeBytes(Selector.encode(stage))
            assertFalse(Selector.mayName(base, stage, plain)) // a temp selector before its rename selects nothing
            File(base, "commit").writeBytes(Selector.encode(stage))
            assertTrue(Selector.mayName(base, stage, plain))
            File(base, "commit").delete()
            File(base, "active").writeBytes(Selector.encode(stage))
            assertTrue(Selector.mayName(base, stage, plain))
            File(base, "commit").writeBytes(Selector.encode(old))
            assertTrue(Selector.mayName(base, stage, plain)) // either selector naming the stage is enough
            File(base, "active").writeBytes(Selector.encode(old))
            assertFalse(Selector.mayName(base, stage, plain))
            File(base, "commit").writeBytes(Selector.encode(old).copyOf(70))
            assertTrue(Selector.mayName(base, stage, plain)) // unreadable commit: keep the media where activation needs it
            File(base, "commit").delete()
            File(base, "active").writeBytes(Selector.encode(old).copyOf(70))
            assertTrue(Selector.mayName(base, stage, plain))
            File(base, "active").writeBytes(Selector.encode(old))
            assertFalse(Selector.mayName(base, stage, plain))
            // a selector whose existence cannot be confirmed either way (I/O error) keeps the media
            assertTrue(Selector.mayName(base, stage) { false })
        } finally { base.deleteRecursively() }
    }
}
