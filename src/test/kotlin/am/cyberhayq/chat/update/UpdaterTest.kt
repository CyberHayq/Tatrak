package am.cyberhayq.chat.update

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class UpdaterTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private fun expect(text: Int, block: () -> Unit) {
        try {
            block()
            fail("no UpdateException")
        } catch (e: UpdateException) {
            assertEquals(context.getString(text), context.getString(e.text))
        }
    }

    @Test
    fun versionComparison() {
        assertTrue(Updater.isNewer("v0.4.0", "0.3.0"))
        assertTrue(Updater.isNewer("v0.3.10", "0.3.9"))
        assertTrue(Updater.isNewer("v1.0", "0.9.9"))
        assertTrue(Updater.isNewer("v0.3.0.1", "0.3.0"))
        assertFalse(Updater.isNewer("v0.3.0", "0.3.0"))
        assertFalse(Updater.isNewer("v0.3", "0.3.0"))
        assertFalse(Updater.isNewer("v0.2.9", "0.3.0"))
        assertFalse(Updater.isNewer("v0.3.0", "0.3.0+playstore"))
        assertFalse(Updater.isNewer("latest", "0.3.0"))
        assertFalse(Updater.isNewer("v0.4.x", "0.3.0"))
        // a pre-release: older than its release, newer than the release before it
        assertTrue(Updater.isNewer("v0.4.0", "0.4.0-beta.1"))
        assertFalse(Updater.isNewer("v0.4.0-beta.1", "0.4.0"))
        assertTrue(Updater.isNewer("v0.4.0-beta.1", "0.3.0"))
        assertFalse(Updater.isNewer("v0.4.0-beta.2", "0.4.0-beta.1"))
    }

    private val prefix = Updater.DOWNLOAD_PREFIX

    private fun releaseJson(version: String, abis: List<String>, base: String = "${prefix}v$version/") =
        JSONObject()
            .put("tag_name", "v$version")
            .put("body", "- notes")
            .put(
                "assets",
                JSONArray(abis.map { abi ->
                    val name = "am.cyberhayq.chat-$version-$abi.apk"
                    JSONObject().put("name", name).put("size", 1000 + abi.length).put("browser_download_url", base + name)
                }),
            )
            .toString()

    private val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal")

    @Test
    fun assetByAbi() {
        val json = releaseJson("0.4.0", allAbis)
        val arm = Updater.parse(json, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals("0.4.0", arm.version)
        assertEquals("- notes", arm.notes)
        assertEquals("${prefix}v0.4.0/am.cyberhayq.chat-0.4.0-arm64-v8a.apk", arm.url)
        assertEquals(1009L, arm.size)
        // the first supported ABI with an asset wins
        assertTrue(Updater.parse(releaseJson("0.4.0", listOf("armeabi-v7a", "universal")), listOf("arm64-v8a", "armeabi-v7a")).url.endsWith("-armeabi-v7a.apk"))
        assertTrue(Updater.parse(json, listOf("riscv64")).url.endsWith("-universal.apk"))
        expect(R.string.tatrak_update_error_release) { Updater.parse(releaseJson("0.4.0", listOf("x86")), listOf("arm64-v8a")) }
        expect(R.string.tatrak_update_error_release) { Updater.parse("not json", listOf("x86")) }
        // an asset pointing elsewhere is refused
        expect(R.string.tatrak_update_error_release) {
            Updater.parse(releaseJson("0.4.0", allAbis, "https://example.com/"), listOf("x86"))
        }
    }

    @Test
    fun urlAcceptance() {
        assertTrue(Updater.acceptUrl("${prefix}v0.4.0/am.cyberhayq.chat-0.4.0-x86.apk"))
        assertFalse(Updater.acceptUrl("http://github.com/CyberHayq/Tatrak/releases/download/v0.4.0/a.apk"))
        assertFalse(Updater.acceptUrl("https://github.com/CyberHayq/Other/releases/download/v0.4.0/a.apk"))
        assertFalse(Updater.acceptUrl("https://github.com.evil.example/CyberHayq/Tatrak/releases/download/a.apk"))
        assertFalse(Updater.acceptUrl("${prefix}../../../Evil/releases/download/a.apk"))
        assertFalse(Updater.acceptUrl("${prefix}%2e%2e/%2e%2e/%2e%2e/Evil/a.apk"))
        assertFalse(Updater.acceptUrl("https://objects.githubusercontent.com/a.apk"))
    }

    @Test
    fun signerSetRule() {
        val release = Updater.sha256(Random.nextBytes(700))
        val other = Updater.sha256(Random.nextBytes(700))
        val ours = "am.cyberhayq.chat"
        fun refusal(apkPackage: String = ours, apk: Set<String> = setOf(release), installed: Set<String> = setOf(release), apkVersion: Long = 5000304) =
            Updater.refusal(apkPackage, ours, apk, installed, apkVersion, 5000204, pinned = release)

        assertNull(refusal())
        assertEquals(R.string.tatrak_update_error_package, refusal(apkPackage = "am.cyberhayq.chat.evil"))
        assertEquals(R.string.tatrak_update_error_signature, refusal(apk = setOf(other)))
        assertEquals(R.string.tatrak_update_error_signature, refusal(apk = setOf(release, other)))
        assertEquals(R.string.tatrak_update_error_signature, refusal(apk = emptySet()))
        // a debug or test install is told so, whatever the APK
        assertEquals(R.string.tatrak_update_error_not_release_build, refusal(installed = setOf(other), apk = setOf(other)))
        assertEquals(R.string.tatrak_update_error_version, refusal(apkVersion = 5000204))
        // the real pin is the release key of the F-Droid recipe
        assertEquals(R.string.tatrak_update_error_not_release_build, Updater.refusal(ours, ours, setOf(release), setOf(release), 2, 1))
        assertEquals(64, Updater.RELEASE_CERT.length)
    }

    @Test
    fun sizeMismatchDeletesTheFile() = runBlocking {
        val file = File(context.cacheDir, "size-test.apk")
        val bytes = Random.nextBytes(200_000)
        var last = 0f
        Updater.copy(ByteArrayInputStream(bytes), file, bytes.size.toLong()) { last = it }
        assertEquals(bytes.size.toLong(), file.length())
        assertEquals(1f, last)
        for (size in listOf(bytes.size - 1L, bytes.size + 1L)) {
            try {
                Updater.copy(ByteArrayInputStream(bytes), file, size) {}
                fail("size $size accepted")
            } catch (e: UpdateException) {
                assertEquals(R.string.tatrak_update_error_size, e.text)
            }
            assertFalse(file.exists())
        }
    }

    @Test
    fun torRefusesEveryRequest() = runBlocking {
        prefs.edit().putBoolean(AppSettings.USE_TOR, true).commit()
        expect(R.string.tatrak_update_error_tor) { Updater.check(context) }
        val release = Release("9.9.9", "", "${prefix}v9.9.9/am.cyberhayq.chat-9.9.9-x86.apk", 10)
        try {
            Updater.download(context, release) {}
            fail("downloaded under Tor")
        } catch (e: UpdateException) {
            assertEquals(R.string.tatrak_update_error_tor, e.text)
        }
        // the automatic check does nothing at all, not even note the attempt
        Updater.checkIfDue(context)
        assertFalse(prefs.contains("tatrak_update_checked_at"))
    }

    @Test
    fun laterHidesUntilTheNextVersion() {
        assertNull(Updater.pending(context))
        prefs.edit().putString(Updater.RELEASE, Release("9.9.9", "n", "${prefix}x", 1).toJson()).commit()
        assertEquals("9.9.9", Updater.pending(context)?.version)
        Updater.dismiss(context, "9.9.9")
        assertNull(Updater.pending(context))
        prefs.edit().putString(Updater.RELEASE, Release("9.9.10", "n", "${prefix}x", 1).toJson()).commit()
        assertNotNull(Updater.pending(context))
        // a stored release that is not newer than this build (installed meanwhile) shows nothing
        prefs.edit().putString(Updater.RELEASE, Release("0.0.1", "n", "${prefix}x", 1).toJson()).commit()
        assertNull(Updater.pending(context))
    }
}
