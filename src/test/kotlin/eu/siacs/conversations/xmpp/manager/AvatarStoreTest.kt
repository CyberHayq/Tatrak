package eu.siacs.conversations.xmpp.manager

import am.cyberhayq.chat.storage.JvmDurable
import am.cyberhayq.chat.storage.media.EncryptedMediaStore
import am.cyberhayq.chat.storage.media.MediaKeyTable
import android.app.Application
import android.graphics.Bitmap
import com.google.common.hash.Hashing
import eu.siacs.conversations.persistance.FileBackend
import eu.siacs.conversations.services.XmppConnectionService
import im.conversations.android.xmpp.model.avatar.Info
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Tatrak: avatars are one encrypted publish under their hash, and read only through the store (step 6). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the readers draw the decoded avatar
@Config(application = Application::class, sdk = [35])
class AvatarStoreTest {
    private val generation = "3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11"
    private var ready: String? = generation
    private lateinit var service: XmppConnectionService
    private lateinit var store: EncryptedMediaStore
    private lateinit var manager: AvatarManager

    @Before fun setUp() {
        JvmDurable.install()
        // attached, never created: the managers and the file backend need only its context
        service = Robolectric.buildService(XmppConnectionService::class.java).get()
    }

    /** Called by each test, after it may have put the cache folder behind a link. */
    private fun start() {
        store = EncryptedMediaStore(service.filesDir, service.cacheDir, object : EncryptedMediaStore.KeySource {
            override fun generation() = ready
            override fun read() = MediaKeyTable.Keys(generation, mapOf(generation to ByteArray(32)))
        })
        EncryptedMediaStore.install(store)
        manager = AvatarManager(service, null)
    }

    @After fun tearDown() {
        EncryptedMediaStore.install(null)
        JvmDurable.uninstall()
    }

    private fun invoke(name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
        val method = AvatarManager::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }
        try {
            return method.invoke(manager, *args)
        } catch (e: InvocationTargetException) {
            throw e.cause!!
        }
    }

    private fun png(color: Int) =
        ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()

    private fun sha1(bytes: ByteArray) = Hashing.sha1().hashBytes(bytes).toString()

    private fun avatars() = File(service.cacheDir, "avatars").list().orEmpty().toSet()

    private fun download(info: Info, body: ByteArray) {
        val response = Response.Builder()
            .request(Request.Builder().url("https://upload.example.org/avatar").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("image/png".toMediaType()))
            .build()
        invoke("write", arrayOf(Info::class.java, Response::class.java), info, response)
    }

    private fun isCached(file: File) = invoke("isCached", arrayOf(File::class.java), file) as Boolean

    private fun assertStored(hash: String, plain: ByteArray) {
        val file = FileBackend.getAvatarFile(service, hash)
        assertEquals("TATRKENC", String(file.readBytes(), 0, 8))
        assertArrayEquals(plain, store.inputStream(file).use { it.readBytes() })
    }

    @Test fun aDownloadedAvatarIsPublishedEncryptedUnderItsHashAndAWrongOneNotAtAll() {
        start()
        val plain = png(0xff00ff00.toInt())
        val hash = sha1(plain)
        assertThrows(IllegalStateException::class.java) { download(Info(sha1(byteArrayOf(1)), plain.size.toLong(), "image/png", 8, 8), plain) }
        assertThrows(IllegalStateException::class.java) { download(Info(hash, plain.size + 1L, "image/png", 8, 8), plain) }
        assertEquals(emptySet<String>(), avatars())
        download(Info(hash, plain.size.toLong(), "image/png", 8, 8), plain)
        assertEquals(setOf(hash), avatars())
        assertStored(hash, plain)
        // the in-band and vCard path
        val other = png(0xff0000ff.toInt())
        (invoke("write", arrayOf(File::class.java, ByteArray::class.java), FileBackend.getAvatarFile(service, sha1(other)), other)
            as com.google.common.util.concurrent.ListenableFuture<*>).get()
        assertStored(sha1(other), other)
        // nothing plain was left in the cache folder
        assertEquals(setOf("avatars"), service.cacheDir.list().orEmpty().toSet())
    }

    @Test fun theOwnAvatarIsPublishedWithItsPlaintextLengthAndHashAndUploadedAsPlaintext() {
        start()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffff0000.toInt()) }
        val info = invoke("resizeAndStoreAvatar", arrayOf(Bitmap::class.java, Bitmap.CompressFormat::class.java, Int::class.javaPrimitiveType!!),
            bitmap, Bitmap.CompressFormat.JPEG, 90) as Info
        val file = FileBackend.getAvatarFile(service, info.id)
        val plain = store.inputStream(file).use { it.readBytes() }
        assertEquals(sha1(plain), info.id)
        assertEquals(plain.size.toLong(), info.bytes)
        assertTrue(file.length() > plain.size) // the file on disk is the encrypted one
        assertEquals("image/jpeg", info.type)
        // the upload: length and body are the plaintext
        val size = store.length(file)
        assertEquals(plain.size.toLong(), size)
        val sent = Buffer().also { HttpUploadManager.storedBody(file, info.type, size).writeTo(it) }
        assertArrayEquals(plain, sent.readByteArray())
        // the PEP data and the vCard photo
        assertArrayEquals(plain, invoke("read", arrayOf(File::class.java), file) as ByteArray)
    }

    @Test fun anAvatarTheStoreCannotOpenIsNotCachedAndShowsNothing() {
        start()
        val plain = png(0xff00ff00.toInt())
        val hash = sha1(plain)
        val file = FileBackend.getAvatarFile(service, hash)
        assertFalse(isCached(file)) // missing
        // an avatar a build before the store left as a plain file
        file.parentFile!!.mkdirs()
        file.writeBytes(plain)
        assertNull(service.fileBackend.getAvatar(hash, 8))
        assertFalse(isCached(file)) // not deleted here: the next publish of the name replaces it

        download(Info(hash, plain.size.toLong(), "image/png", 8, 8), plain)
        assertTrue(isCached(file))
        // while local storage is not ready nothing can be said about the file: it stays
        ready = null
        assertFalse(isCached(file))
        assertTrue(file.exists())
        ready = generation
        // a damaged one is not cached
        val bytes = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertFalse(isCached(file))
    }

    @Test fun aLargeAvatarDamagedInItsFirstSegmentIsNotCached() {
        start()
        // three segments; the last one intact, so only reading the whole file finds the damage
        val plain = ByteArray(150_000) { (it * 7).toByte() }
        val hash = sha1(plain)
        download(Info(hash, plain.size.toLong(), "image/png", 8, 8), plain)
        val file = FileBackend.getAvatarFile(service, hash)
        assertTrue(isCached(file))
        file.writeBytes(file.readBytes().also { it[100] = (it[100].toInt() xor 1).toByte() })
        assertFalse(isCached(file))
    }

    @Test fun theReadersDecodeThroughTheStore() {
        start()
        val plain = png(0xff00ff00.toInt())
        val hash = sha1(plain)
        download(Info(hash, plain.size.toLong(), "image/png", 8, 8), plain)
        val avatar = service.fileBackend.getAvatar(hash, 8)
        assertNotNull(avatar)
        assertEquals(0xff00ff00.toInt(), avatar!!.getPixel(4, 4))
        val tile = service.fileBackend.cropCenter(FileBackend.getAvatarFile(service, hash), 8, 4)
        assertEquals(0xff00ff00.toInt(), tile!!.getPixel(2, 4))
    }

    @Test fun everythingWorksWithTheCacheFolderBehindALink() {
        // on a device the cache folder is /data/user/0/<pkg>/cache, a link to /data/data/<pkg>/cache
        val cache = service.cacheDir
        val real = File(cache.parentFile, "real-cache")
        assertTrue(real.mkdirs())
        cache.deleteRecursively()
        Files.createSymbolicLink(cache.toPath(), real.toPath())
        assertTrue(Files.isSymbolicLink(service.cacheDir.toPath()))
        start()
        val plain = png(0xff00ff00.toInt())
        val hash = sha1(plain)
        download(Info(hash, plain.size.toLong(), "image/png", 8, 8), plain)
        assertEquals(setOf(hash), File(real, "avatars").list()!!.toSet())
        val file = FileBackend.getAvatarFile(service, hash)
        assertTrue(store.isEncrypted(file))
        assertTrue(isCached(file))
        assertEquals(0xff00ff00.toInt(), service.fileBackend.getAvatar(hash, 8)!!.getPixel(4, 4))
    }
}
