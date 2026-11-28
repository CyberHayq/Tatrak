package am.cyberhayq.chat.update

import android.app.Application
import android.content.Context
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.core.content.pm.PackageInfoCompat
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
        // pre-releases of the same numbers: SemVer 2.0 §11
        assertTrue(Updater.isNewer("v0.4.0-beta.2", "0.4.0-beta.1"))
        assertFalse(Updater.isNewer("v0.4.0-beta.1", "0.4.0-beta.2"))
        assertTrue(Updater.isNewer("v0.4.0-beta.10", "0.4.0-beta.9"))
        assertFalse(Updater.isNewer("v0.4.0-beta.9", "0.4.0-beta.10"))
        assertTrue(Updater.isNewer("v0.4.0-rc.1", "0.4.0-beta.10"))
        assertFalse(Updater.isNewer("v0.4.0-beta.10", "0.4.0-rc.1"))
        assertTrue(Updater.isNewer("v0.4.0", "0.4.0-rc.1"))
        assertFalse(Updater.isNewer("v0.4.0-rc.1", "0.4.0"))
        // numeric below alphanumeric, a shorter list below a longer one with the same start
        assertTrue(Updater.isNewer("v0.4.0-beta.x", "0.4.0-beta.2"))
        assertTrue(Updater.isNewer("v0.4.0-beta.1.1", "0.4.0-beta.1"))
        assertFalse(Updater.isNewer("v0.4.0-beta", "0.4.0-beta.1"))
        assertFalse(Updater.isNewer("v0.4.0-beta.1", "0.4.0-beta.1"))
        // the build part is still ignored
        assertFalse(Updater.isNewer("v0.4.0-beta.1+b7", "0.4.0-beta.1+b3"))
        assertTrue(Updater.isNewer("v0.4.0-beta.2+b1", "0.4.0-beta.1+b9"))
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

    private fun entry(version: String, abis: List<String> = allAbis, draft: Boolean = false) =
        JSONObject(releaseJson(version, abis)).put("draft", draft).put("prerelease", '-' in version)

    @Test
    fun newestOfTheReleasesList() {
        val arm = listOf("arm64-v8a", "armeabi-v7a")
        // GitHub lists by creation date, not by version; a draft is skipped even when highest
        val list =
            JSONArray(listOf(
                entry("0.4.0-beta.9"),
                entry("0.5.0", draft = true),
                entry("0.4.0-beta.10"),
                JSONObject().put("tag_name", "nightly").put("assets", JSONArray()),
                entry("0.3.0"),
                entry("0.4.0-beta.2"),
            )).toString()
        val release = Updater.newest(list, listOf("x86_64"), "0.4.0-beta.1")!!
        assertEquals("0.4.0-beta.10", release.version)
        assertEquals("${prefix}v0.4.0-beta.10/am.cyberhayq.chat-0.4.0-beta.10-x86_64.apk", release.url)
        // a release beats its own pre-releases
        assertEquals("0.4.0", Updater.newest(JSONArray(list).put(entry("0.4.0")).toString(), arm, "0.4.0-beta.1")?.version)
        // the newest beta has no APK for this device: the next newer release is offered
        val x86Only = JSONArray(list).put(entry("0.4.0-rc.1", listOf("x86_64"))).toString()
        assertEquals("0.4.0-beta.10", Updater.newest(x86Only, arm, "0.4.0-beta.1")?.version)
        // nothing newer than the app: up to date, even when the older releases have no usable APK
        val old = JSONArray(listOf(entry("0.3.0", listOf("mips")), entry("0.4.0-beta.1", emptyList()), entry("0.5.0", draft = true))).toString()
        assertNull(Updater.newest(old, arm, "0.4.0-beta.1"))
        assertNull(Updater.newest("[]", arm, "0.4.0-beta.1"))
        // newer ones exist but none has an APK for this device
        expect(R.string.tatrak_update_error_release) { Updater.newest(x86Only, listOf("riscv64"), "0.4.0-beta.10") }
        expect(R.string.tatrak_update_error_release) { Updater.newest("{}", arm, "0.4.0-beta.1") }
    }

    /** A list entry with its own notes; null is GitHub's `"body": null`. */
    private fun noted(version: String, body: String?, abis: List<String> = allAbis, draft: Boolean = false) =
        entry(version, abis, draft).put("body", body ?: JSONObject.NULL)

    private fun offer(version: String, notes: String) = Release(version, notes, "${prefix}v$version/am.cyberhayq.chat-$version-x86.apk", 1004)

    @Test
    fun skippedBetasNotesNewestFirst() {
        // created out of version order; a draft between them is not a release; the installed one and older ones are left out
        val list =
            JSONArray(listOf(
                noted("0.6.0-beta.2", "### Fixes\n- two"),
                noted("0.6.0-beta.4", "- four"),
                noted("0.6.0-beta.3.1", "- draft", draft = true),
                noted("0.6.0-beta.3", "- three\r\n"),
                noted("0.6.0-beta.1", "- installed"),
                noted("0.5.0", "- old"),
            )).toString()
        val three = Updater.withSkippedNotes(offer("0.6.0-beta.4", "- four"), list, "0.6.0-beta.1")
        assertEquals("# 0.6.0-beta.4\n- four\n\n# 0.6.0-beta.3\n- three\n\n# 0.6.0-beta.2\n### Fixes\n- two", three.notes)
        // only the notes change
        assertEquals(offer("0.6.0-beta.4", "- four").copy(notes = three.notes), three)
        // two skipped: an offer below the newest listed (that one without an APK for this device) leaves out what is above it
        assertEquals(
            "# 0.6.0-beta.3\n- three\n\n# 0.6.0-beta.2\n### Fixes\n- two",
            Updater.withSkippedNotes(offer("0.6.0-beta.3", "- three"), list, "0.6.0-beta.1").notes,
        )
        // as check() builds it with betas on, from newest()
        assertEquals(three.notes, Updater.withSkippedNotes(Updater.newest(list, listOf("x86"), "0.6.0-beta.1")!!, list, "0.6.0-beta.1").notes)
    }

    /** Beta versions off: the offer is a release, but its notes list only what changed since its last beta, so the betas' notes come too. */
    @Test
    fun skippedNotesOfAReleaseIncludeItsBetas() {
        val list =
            JSONArray(listOf(
                noted("0.6.0", "- six"),
                noted("0.6.0-beta.3", "- beta three"),
                noted("0.6.0-beta.2", "- beta two"),
                noted("0.6.0-beta.1", "- beta one"),
                noted("0.5.1", "- installed"),
            )).toString()
        val stable = offer("0.6.0", "- six")
        assertEquals(
            "# 0.6.0\n- six\n\n# 0.6.0-beta.3\n- beta three\n\n# 0.6.0-beta.2\n- beta two\n\n# 0.6.0-beta.1\n- beta one",
            Updater.withSkippedNotes(stable, list, "0.5.1").notes,
        )
        // from a beta: only what it has not had yet
        assertEquals("# 0.6.0\n- six\n\n# 0.6.0-beta.3\n- beta three", Updater.withSkippedNotes(stable, list, "0.6.0-beta.2").notes)
        // GitHub's prerelease flag is not read
        val flagged = JSONArray(list).put(noted("0.5.2", "- five two").put("prerelease", true)).toString()
        assertTrue(Updater.withSkippedNotes(stable, flagged, "0.5.1").notes.endsWith("# 0.5.2\n- five two"))
    }

    @Test
    fun skippedReleaseWithoutAnApkStillGivesItsNotes() {
        val list = JSONArray(listOf(noted("0.6.0-beta.3", "- three", listOf("x86")), noted("0.6.0-beta.2", "- two", emptyList()))).toString()
        assertEquals("# 0.6.0-beta.3\n- three\n\n# 0.6.0-beta.2\n- two", Updater.withSkippedNotes(offer("0.6.0-beta.3", "- three"), list, "0.6.0-beta.1").notes)
    }

    @Test
    fun singleNewerReleaseKeepsItsNotes() {
        val notes = "Intro\r\n\r\n### Interface\r\n- one\r\n"
        val release = offer("0.6.0-beta.2", notes)
        val list = JSONArray(listOf(noted("0.6.0-beta.2", notes), noted("0.6.0-beta.1", "- installed"), noted("0.5.0", "- old"))).toString()
        assertEquals(release, Updater.withSkippedNotes(release, list, "0.6.0-beta.1"))
        // the offer not in the list's page, an unreadable list: unchanged too
        assertEquals(release, Updater.withSkippedNotes(release, "[]", "0.6.0-beta.1"))
        assertEquals(release, Updater.withSkippedNotes(release, "{}", "0.6.0-beta.1"))
    }

    @Test
    fun emptyBodiesAreSkipped() {
        val list = JSONArray(listOf(noted("0.6.0-beta.5", "- five"), noted("0.6.0-beta.4", ""), noted("0.6.0-beta.3", null), noted("0.6.0-beta.2", " \n "))).toString()
        assertEquals("# 0.6.0-beta.5\n- five", Updater.withSkippedNotes(offer("0.6.0-beta.5", "- five"), list, "0.6.0-beta.1").notes)
        // an offer without notes of its own still shows the skipped ones under their version
        assertEquals("# 0.6.0-beta.5\n- five", Updater.withSkippedNotes(offer("0.6.0-beta.6", ""), list, "0.6.0-beta.1").notes)
        // GitHub's "body": null is no notes, not the text "null"
        assertEquals("", Updater.parse(noted("0.6.0", null).toString(), listOf("x86")).notes)
    }

    /** Every release body starts with the same install preamble; joined, it is kept only for the offered release. */
    @Test
    fun skippedPreambleIsDropped() {
        val preamble = "A pre-release for testing. The in-app updater offers it only with Beta versions turned on.\r\n\r\nPick `arm64-v8a`. Signing certificate SHA-256: `3bae`\r\n\r\n"
        val list =
            JSONArray(listOf(
                noted("0.6.0-beta.3", preamble + "### Interface\r\n- three\r\n"),
                noted("0.6.0-beta.2", "Only a preamble, no heading."),
            )).toString()
        assertEquals(
            "# 0.6.0-beta.4\n${preamble.trim()}\r\n\r\n### Fixes\n- four\n\n# 0.6.0-beta.3\n### Interface\r\n- three\n\n# 0.6.0-beta.2\nOnly a preamble, no heading.",
            Updater.withSkippedNotes(offer("0.6.0-beta.4", preamble + "### Fixes\n- four"), list, "0.6.0-beta.1").notes,
        )
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

    /** The test's plain Application makes HttpConnectionManager.okHttpClient() throw, as a failed TLS setup does. */
    @Test
    fun clientSetupFailureIsANetworkError() = runBlocking {
        expect(R.string.tatrak_update_error_network) { Updater.check(context) }
        try {
            Updater.download(context, Release("9.9.9", "", "${prefix}v9.9.9/am.cyberhayq.chat-9.9.9-x86.apk", 10)) {}
            fail("downloaded without a client")
        } catch (e: UpdateException) {
            assertEquals(R.string.tatrak_update_error_network, e.text)
        }
    }

    /**
     * Android 9's archive parser with an actually signed APK (tools/update-test-apk/make.sh): it
     * collects an archive's certificates only when GET_SIGNATURES is asked for, too. Only the
     * certificate reading is replaced ([ShadowApkSignatureVerifier]).
     */
    @Test
    @Config(sdk = [28], shadows = [ShadowApkSignatureVerifier::class])
    fun archiveSignersFromTheRealParser() {
        val file = File(context.cacheDir, "signed.apk")
        javaClass.getResourceAsStream("/update-test-apk/signed-apk.bin")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val apk = Updater.archive(context, file)
        assertEquals("am.cyberhayq.chat.updatetest", apk?.packageName)
        assertEquals(7L, apk?.let(PackageInfoCompat::getLongVersionCode))
        // Robolectric's SigningInfo shadow drops the parser's SigningDetails: read them as getApkContentsSigners() does on a device
        val details = SigningInfo::class.java.getDeclaredField("mSigningDetails").apply { isAccessible = true }.get(apk?.signingInfo ?: fail("no signers"))
        @Suppress("UNCHECKED_CAST") val signatures = details.javaClass.getField("signatures").get(details) as Array<Signature>
        assertEquals(setOf("8ac2eaadc696b11785cd93ec47ccd8c46834c23ff9d70f20ec3646893bca62b6"), signatures.map { Updater.sha256(it.toByteArray()) }.toSet())
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

    @Test
    fun storedPreReleaseHiddenWithBetasOff() {
        prefs.edit().putString(Updater.RELEASE, Release("9.9.9-beta.1", "n", "${prefix}x", 1).toJson()).commit()
        assertNull(Updater.pending(context))
        prefs.edit().putBoolean(Updater.PRERELEASE, true).commit()
        assertEquals("9.9.9-beta.1", Updater.pending(context)?.version)
        prefs.edit().putBoolean(Updater.PRERELEASE, false).commit()
        assertNull(Updater.pending(context))
        // a release still shows with betas off
        prefs.edit().putString(Updater.RELEASE, Release("9.9.9", "n", "${prefix}x", 1).toJson()).commit()
        assertEquals("9.9.9", Updater.pending(context)?.version)
    }
}
