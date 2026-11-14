package am.cyberhayq.chat.storage.media

import am.cyberhayq.chat.storage.JvmDurable
import android.app.Application
import androidx.preference.PreferenceManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class VoiceStreamTest {
    private val generation = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11"
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var store: EncryptedMediaStore
    private val attachments get() = File(app.filesDir, "attachments").list().orEmpty().toList()
    private val temporaries get() = File(app.filesDir, "tatrak_tmp").list().orEmpty().toList()

    @Before fun setUp() {
        JvmDurable.install()
        store = EncryptedMediaStore(app.filesDir, app.cacheDir, object : EncryptedMediaStore.KeySource {
            override fun generation() = generation
            override fun read() = MediaKeyTable.Keys(generation, mapOf(generation to ByteArray(32)))
        })
        EncryptedMediaStore.install(store)
    }

    @After fun tearDown() {
        EncryptedMediaStore.install(null)
        JvmDurable.uninstall()
    }

    private fun voice(size: Int) = ByteArray(size) { (it * 7).toByte() }

    private fun writer() = store.createAttachment(store.attachmentFile("voice.aac"))

    @Test
    fun theWholeStreamIsPublishedWhenKept() {
        val data = voice(200_000) // several segments
        val file = VoiceStream.pump(ByteArrayInputStream(data), writer()) { true }
        assertNotNull(file)
        assertTrue(file!!.name.endsWith(".aac"))
        assertArrayEquals(data, store.inputStream(file).use { it.readBytes() })
        assertEquals(listOf(file.name), attachments)
        assertEquals(emptyList<String>(), temporaries)
    }

    @Test
    fun aCancelledRecordingLeavesNothing() {
        assertNull(VoiceStream.pump(ByteArrayInputStream(voice(1000)), writer()) { false })
        assertEquals(emptyList<String>(), attachments)
        assertEquals(emptyList<String>(), temporaries)
    }

    @Test
    fun aFailingWriterDropsTheFileButTheStreamIsReadToItsEnd() {
        val data = voice(1_000_000)
        var read = 0
        val input = object : InputStream() {
            override fun read() = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (read == data.size) return -1
                val count = minOf(len, data.size - read)
                System.arraycopy(data, read, b, off, count)
                read += count
                return count
            }
        }
        val writer = writer()
        writer.abort() // the file under it is closed: the first full segment fails to write
        var asked = false
        assertNull(VoiceStream.pump(input, writer) { asked = true; true })
        assertEquals(data.size, read)
        assertFalse(asked) // a failed recording is not kept, whatever the screen says
        assertEquals(emptyList<String>(), attachments)
        assertEquals(emptyList<String>(), temporaries)
    }

    @Test
    fun theFilePathEncryptsTheRecordingAndDeletesThePlainFileEitherWay() {
        val folder = File(app.cacheDir, "Recordings").apply { mkdirs() }
        val plain = File(folder, "RECORDING_1.m4a").apply { writeBytes(voice(5000)) }
        val file = VoiceStream.publish(store, plain, keep = true)!!
        assertTrue(file.name.endsWith(".m4a"))
        assertArrayEquals(voice(5000), store.inputStream(file).use { it.readBytes() })
        assertFalse(plain.exists())

        plain.writeBytes(voice(10))
        assertNull(VoiceStream.publish(store, plain, keep = false))
        assertFalse(plain.exists())
        assertEquals(listOf(file.name), attachments)
    }

    @Test
    fun aFailedPipeStartRecordsNothingAndSwitchesTheDeviceToTheFile() {
        assertFalse(VoiceStream.usesFile(app))
        assertNull(VoiceStream.start(app) { throw RuntimeException("start failed.") })
        assertTrue(VoiceStream.usesFile(app))
        assertTrue(PreferenceManager.getDefaultSharedPreferences(app).getBoolean("tatrak_voice_pipe_failed", false))
        assertEquals(emptyList<String>(), attachments)
        assertEquals(emptyList<String>(), temporaries)

        // the file path then: a failed start leaves no plain file either
        assertNull(VoiceStream.start(app) { throw RuntimeException("start failed.") })
        assertEquals(emptyList<String>(), File(app.cacheDir, "Recordings").list().orEmpty().toList())
    }
}
