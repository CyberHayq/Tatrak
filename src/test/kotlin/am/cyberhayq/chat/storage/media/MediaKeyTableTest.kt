package am.cyberhayq.chat.storage.media

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class MediaKeyTableTest {
    private val platform = SQLiteDatabase.create(null)
    private val db = supportDatabase(platform)
    private val generation = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11"
    private val older = "9a1c0c0e-58f1-4d7a-b7c2-5f6d3e2a4b99"

    @After fun close() = platform.close()

    private fun rows(): List<Triple<String, ByteArray, Int>> =
        platform.rawQuery("SELECT generation, master_key, own FROM tatrak_media_key ORDER BY own DESC, generation", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getBlob(1), c.getInt(2))) }
        }

    private fun refused(block: () -> Unit) {
        try { block(); fail("expected a refusal") } catch (e: SQLiteException) { } catch (e: IllegalStateException) { } catch (e: IllegalArgumentException) { }
    }

    @Test fun theOwnKeyAppearsOnlyWithTheTableAndUnbound() {
        assertNull(MediaKeyTable.read(db).own) // no table yet
        MediaKeyTable.create(db)
        val (name, key, own) = rows().single()
        assertEquals("pending", name)
        assertEquals(32, key.size)
        assertEquals(1, own)
        assertFalse(key.all { it == 0.toByte() })
        // unbound, it is not a usable key
        assertNull(MediaKeyTable.read(db).own)
        assertTrue(MediaKeyTable.read(db).byGeneration.isEmpty())
        // the table is created once: there is no second chance to make a key
        refused { MediaKeyTable.create(db) }
        assertArrayEquals(key, rows().single().second)
        // another database gets another key
        SQLiteDatabase.create(null).use { other ->
            MediaKeyTable.create(supportDatabase(other))
            other.rawQuery("SELECT master_key FROM tatrak_media_key", null).use { it.moveToFirst(); assertFalse(key.contentEquals(it.getBlob(0))) }
        }
    }

    @Test fun activationBindsTheOwnKeyToItsGenerationOnce() {
        MediaKeyTable.create(db)
        val key = rows().single().second
        assertTrue(MediaKeyTable.bindOwn(db, generation))
        assertTrue(MediaKeyTable.bindOwn(db, generation)) // a repeated activation changes nothing
        val keys = MediaKeyTable.read(db)
        assertEquals(generation, keys.own)
        assertArrayEquals(key, keys.byGeneration.getValue(generation))
        assertEquals(1, rows().size)
        // the key of one generation is never renamed to another
        refused { MediaKeyTable.bindOwn(db, older) }
        refused { MediaKeyTable.bindOwn(db, "pending") }
        refused { MediaKeyTable.bindOwn(db, "not-a-uuid") }
        assertEquals(generation, rows().single().first)
    }

    @Test fun aNormalImportsStageKeepsNoOwnKey() {
        MediaKeyTable.create(db)
        MediaKeyTable.removePending(db)
        assertTrue(rows().isEmpty())
        assertFalse(MediaKeyTable.bindOwn(db, generation)) // and activation makes none
        assertTrue(rows().isEmpty())
    }

    @Test fun aBoundOwnKeyIsNotRemovedAsAStageLeftover() {
        MediaKeyTable.create(db)
        MediaKeyTable.bindOwn(db, generation)
        MediaKeyTable.removePending(db)
        assertEquals(generation, MediaKeyTable.read(db).own)
    }

    @Test fun aDatabaseThatLostItsOwnKeyNeverGetsANewOne() {
        MediaKeyTable.create(db)
        MediaKeyTable.bindOwn(db, generation)
        platform.execSQL("DELETE FROM tatrak_media_key WHERE own = 1")
        assertFalse(MediaKeyTable.bindOwn(db, generation))
        MediaKeyTable.addImported(db, older, ByteArray(32) { 7 })
        val keys = MediaKeyTable.read(db)
        assertNull(keys.own)
        assertEquals(setOf(older), keys.byGeneration.keys)
        assertEquals(0, rows().single().third)
    }

    @Test fun aKeyFromABackupNeverReplacesOne() {
        MediaKeyTable.create(db)
        MediaKeyTable.bindOwn(db, generation)
        val own = rows().single().second
        MediaKeyTable.addImported(db, older, ByteArray(32) { 7 })
        MediaKeyTable.addImported(db, older, ByteArray(32) { 8 })      // the same generation again
        MediaKeyTable.addImported(db, generation, ByteArray(32) { 9 }) // the own generation
        val keys = MediaKeyTable.read(db)
        assertEquals(generation, keys.own)
        assertArrayEquals(own, keys.byGeneration.getValue(generation))
        assertArrayEquals(ByteArray(32) { 7 }, keys.byGeneration.getValue(older))
        assertEquals(2, rows().size)
        refused { MediaKeyTable.addImported(db, older, ByteArray(31)) }
        refused { MediaKeyTable.addImported(db, "pending", ByteArray(32)) }
    }

    @Test fun theSchemaRefusesASecondOwnKeyAndAShortKey() {
        MediaKeyTable.create(db)
        refused { platform.execSQL("INSERT INTO tatrak_media_key VALUES (?, ?, 1)", arrayOf<Any>(older, ByteArray(32))) }
        refused { platform.execSQL("INSERT INTO tatrak_media_key VALUES (?, ?, 0)", arrayOf<Any>(older, ByteArray(31))) }
        refused { platform.execSQL("INSERT INTO tatrak_media_key VALUES (?, ?, 0)", arrayOf<Any>(older, "a".repeat(32))) }
        refused { platform.execSQL("INSERT INTO tatrak_media_key VALUES (?, ?, 2)", arrayOf<Any>(older, ByteArray(32))) }
        assertEquals(1, rows().size)
    }

    @Test fun aDatabaseWithoutTheTableHasNoKeys() {
        assertFalse(MediaKeyTable.bindOwn(db, generation))
        MediaKeyTable.removePending(db)
        assertTrue(MediaKeyTable.read(db).byGeneration.isEmpty())
        refused { MediaKeyTable.addImported(db, older, ByteArray(32)) } // no table to add to: the caller must see it
    }
}
