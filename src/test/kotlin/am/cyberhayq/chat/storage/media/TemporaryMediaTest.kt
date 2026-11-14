package am.cyberhayq.chat.storage.media

import am.cyberhayq.chat.storage.JvmDurable
import android.app.Application
import eu.siacs.conversations.ui.util.Attachment
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The camera's capture and the plain leftovers of "Temporary plaintext". */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class TemporaryMediaTest {
    private val generation = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11"
    private val app = RuntimeEnvironment.getApplication()
    private var ready: String? = generation
    private lateinit var store: EncryptedMediaStore
    private val camera get() = File(app.cacheDir, "Camera").apply { mkdirs() }

    @Before fun setUp() {
        JvmDurable.install()
        store = EncryptedMediaStore(app.filesDir, app.cacheDir, object : EncryptedMediaStore.KeySource {
            override fun generation() = ready
            override fun read() = MediaKeyTable.Keys(generation, mapOf(generation to ByteArray(32)))
        })
        EncryptedMediaStore.install(store)
    }

    @After fun tearDown() {
        EncryptedMediaStore.install(null)
        JvmDurable.uninstall()
    }

    private fun capture(name: String = "IMG_1.jpg") = File(camera, name).apply { writeBytes(ByteArray(3000) { it.toByte() }) }

    private fun draft(): Pair<Attachment, File> {
        val stored = TemporaryMedia.encryptCapture(app, capture())!!
        // as Attachment.of(context, uri, IMAGE) makes it; that one's MimeUtils throws on the JVM
        return Attachment.of(UUID.randomUUID(), stored, "image/jpeg") to stored
    }

    @Test
    fun aCaptureIsEncryptedOnReturnAndItsPlainFileDeleted() {
        val plain = capture()
        val stored = TemporaryMedia.encryptCapture(app, plain)!!
        assertTrue(store.isEncrypted(stored) && stored.name.endsWith(".jpg"))
        assertArrayEquals(ByteArray(3000) { it.toByte() }, store.inputStream(stored).use { it.readBytes() })
        assertFalse(plain.exists())
    }

    @Test
    fun aCaptureThatCannotBeEncryptedStaysPlain() {
        ready = null // local storage not ready
        val plain = capture()
        assertNull(TemporaryMedia.encryptCapture(app, plain))
        assertTrue(plain.exists())
        assertEquals(0, File(app.filesDir, "attachments").list().orEmpty().size)
    }

    @Test
    fun onlyADiscardedDraftIsDeleted() {
        val (discarded, discardedFile) = draft()
        val (sent, sentFile) = draft()
        // a message's own file reached by a file:// URI, as a forward or the media viewer build it
        val (_, messageFile) = draft()
        val message = Attachment.of(UUID.randomUUID(), messageFile, "image/jpeg")
        val drafts = hashSetOf(discarded.uuid, sent.uuid)

        TemporaryMedia.deleteDrafts(drafts, listOf(discarded, message))
        assertFalse(discardedFile.exists())
        assertTrue(messageFile.exists()) // never: it is not a draft of this screen
        assertEquals(setOf(sent.uuid), drafts)
        // Send hands the draft to a message without discarding it: a later chat switch drops other previews only
        TemporaryMedia.deleteDrafts(drafts, emptyList())
        assertTrue(sentFile.exists())
        // a preview of the plain capture (encryption failed) is not a draft
        val plain = Attachment.of(UUID.randomUUID(), capture("IMG_2.jpg"), "image/jpeg")
        TemporaryMedia.deleteDrafts(drafts, listOf(plain))
        assertTrue(File(camera, "IMG_2.jpg").exists())
    }

    @Test
    fun atStartRecordingsAndAvatarEncodesGoAndCapturesOnlyAfter24Hours() {
        val recordings = File(app.cacheDir, "Recordings").apply { mkdirs() }
        File(recordings, "RECORDING_1.m4a").writeBytes(ByteArray(10))
        val avatar = TemporaryMedia.avatarDirectory(app).apply { mkdirs() }
        File(avatar, UUID.randomUUID().toString()).writeBytes(ByteArray(10))
        val now = System.currentTimeMillis()
        val old = capture("IMG_old.jpg").apply { setLastModified(now - TimeUnit.HOURS.toMillis(25)) }
        val young = capture("IMG_young.jpg").apply { setLastModified(now - TimeUnit.HOURS.toMillis(23)) }
        val other = File(app.cacheDir, "keep.jpg").apply { writeBytes(ByteArray(10)) }
        TemporaryMedia.deleteLeftovers(app, now)
        assertEquals(0, recordings.list()!!.size)
        assertEquals(0, avatar.list()!!.size)
        assertFalse(old.exists())
        assertTrue(young.exists())
        assertTrue(other.exists())
    }

    @Test
    fun aCroppedAvatarLivesInTheEncoderFolderAndOnlyThatIsDeleted() {
        am.cyberhayq.chat.testing.FileProviderCache.clear()
        val crop = TemporaryMedia.cropOutput(app)
        assertEquals("content", crop.scheme) // the cropper refuses file:// output
        val file = File(TemporaryMedia.avatarDirectory(app), crop.lastPathSegment!!)
        file.writeBytes(byteArrayOf(1))
        val other = File(app.cacheDir, "Camera/other.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(2)) }
        TemporaryMedia.deleteCrop(app, eu.siacs.conversations.persistance.FileBackend.getUriForFile(app, other))
        TemporaryMedia.deleteCrop(app, android.net.Uri.fromFile(file))
        assertTrue(other.exists() && file.exists()) // not a crop URI: left alone
        TemporaryMedia.deleteCrop(app, crop)
        assertFalse(file.exists())
        TemporaryMedia.deleteCrop(app, null)
    }
}
