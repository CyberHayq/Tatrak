package am.cyberhayq.chat.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.preference.PreferenceManager
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.BuildConfig
import eu.siacs.conversations.R
import eu.siacs.conversations.http.HttpConnectionManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A failure with the text the user sees. */
class UpdateException(val text: Int, cause: Throwable? = null) : Exception(cause)

/** A newer release found on GitHub: its version, notes and the APK for this device. */
data class Release(val version: String, val notes: String, val url: String, val size: Long) {
    fun toJson(): String =
        JSONObject().put("version", version).put("notes", notes).put("url", url).put("size", size).toString()

    companion object {
        fun fromJson(json: String?): Release? =
            try {
                json?.let { JSONObject(it) }?.let { Release(it.getString("version"), it.getString("notes"), it.getString("url"), it.getLong("size")) }
            } catch (e: Exception) {
                null
            }
    }
}

/**
 * Tatrak: updates from the public repository's GitHub releases. The downloaded APK is installed only
 * when it is our package, signed by exactly the installed app's signers, among them the pinned
 * release certificate, and has a higher versionCode. Never runs while Tor is on.
 */
object Updater {
    const val CHECK = "tatrak_update_check"
    const val RELEASE = "tatrak_update_release"
    const val DISMISSED = "tatrak_update_dismissed"
    const val PRERELEASE = "tatrak_update_prerelease"
    private const val CHECKED_AT = "tatrak_update_checked_at"
    private const val LATEST = "https://api.github.com/repos/CyberHayq/Tatrak/releases/latest"
    /** Newest first by creation date; [newest] still ranks by version. */
    private const val RELEASES = "https://api.github.com/repos/CyberHayq/Tatrak/releases?per_page=10"
    const val DOWNLOAD_PREFIX = "https://github.com/CyberHayq/Tatrak/releases/download/"
    /** AllowedAPKSigningKeys of tools/fdroid/am.cyberhayq.chat.yml. */
    const val RELEASE_CERT = "3bae017d66664f88502ab51a6868621c1f93fd47004b3666796d3cd9a6946d9b"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** `v1.2.3` or `1.2.3+playstore` as numbers; null when it is not a plain dotted version. */
    fun version(text: String): List<Int>? =
        text.removePrefix("v").substringBefore('+').substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }

    fun isNewer(tag: String, current: String): Boolean {
        val a = version(tag) ?: return false
        val b = version(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return comparePreRelease(preRelease(tag), preRelease(current)) > 0
    }

    /** The dot-separated identifiers after `-` (before any `+build`); null for a release. */
    private fun preRelease(version: String): List<String>? =
        version.substringBefore('+').substringAfter('-', "").takeIf { it.isNotEmpty() }?.split('.')

    private fun isPreRelease(version: String) = preRelease(version) != null

    /** SemVer 2.0 §11 for equal numbers: beta.9 < beta.10 < rc.1 < the release itself. */
    private fun comparePreRelease(a: List<String>?, b: List<String>?): Int {
        if (a == null || b == null) return (if (a == null) 1 else 0) - (if (b == null) 1 else 0)
        for ((x, y) in a.zip(b)) {
            val xNumeric = x.isNotEmpty() && x.all { it in '0'..'9' }
            val yNumeric = y.isNotEmpty() && y.all { it in '0'..'9' }
            val d =
                when {
                    xNumeric && yNumeric -> x.toBigInteger().compareTo(y.toBigInteger())
                    xNumeric -> -1
                    yNumeric -> 1
                    else -> x.compareTo(y)
                }
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** Only our releases' download path, after OkHttp resolves `..` and the like. */
    fun acceptUrl(url: String): Boolean =
        url.startsWith(DOWNLOAD_PREFIX) && url.toHttpUrlOrNull()?.toString()?.startsWith(DOWNLOAD_PREFIX) == true

    /** The `releases/latest` answer: the first of [abis] with an asset, else `universal`. */
    fun parse(json: String, abis: List<String>): Release {
        try {
            val release = JSONObject(json)
            val version = release.getString("tag_name").removePrefix("v")
            val assets = release.getJSONArray("assets")
            val byName = (0 until assets.length()).map(assets::getJSONObject).associateBy { it.getString("name") }
            val asset = (abis + "universal").firstNotNullOfOrNull { byName["am.cyberhayq.chat-$version-$it.apk"] }
                ?: throw UpdateException(R.string.tatrak_update_error_release)
            val url = asset.getString("browser_download_url")
            val size = asset.getLong("size")
            if (!acceptUrl(url) || size <= 0) throw UpdateException(R.string.tatrak_update_error_release)
            return Release(version, release.optString("body"), url, size)
        } catch (e: JSONException) {
            throw UpdateException(R.string.tatrak_update_error_release, e)
        }
    }

    /**
     * The `releases` list answer: the highest non-draft release newer than [current] that [parse]
     * accepts, so one without an APK for this device does not hide an older one; null when none is newer.
     */
    fun newest(json: String, abis: List<String>, current: String): Release? {
        val newer =
            try {
                val releases = JSONArray(json)
                (0 until releases.length()).map(releases::getJSONObject).filter { !it.optBoolean("draft") && isNewer(it.optString("tag_name"), current) }
            } catch (e: JSONException) {
                throw UpdateException(R.string.tatrak_update_error_release, e)
            }
        if (newer.isEmpty()) return null
        val highestFirst = newer.sortedWith { a, b -> if (isNewer(a.getString("tag_name"), b.getString("tag_name"))) -1 else if (isNewer(b.getString("tag_name"), a.getString("tag_name"))) 1 else 0 }
        return highestFirst.firstNotNullOfOrNull {
            try {
                parse(it.toString(), abis)
            } catch (e: UpdateException) {
                null
            }
        } ?: throw UpdateException(R.string.tatrak_update_error_release)
    }

    /** The reason to refuse an APK, or null; signer sets are SHA-256 hex of each certificate. */
    fun refusal(
        apkPackage: String?,
        ourPackage: String,
        apkSigners: Set<String>,
        installedSigners: Set<String>,
        apkVersion: Long,
        installedVersion: Long,
        pinned: String = RELEASE_CERT,
    ): Int? =
        when {
            // first: a debug or benchmark install gets the reason that it is one, not "wrong package"
            pinned !in installedSigners -> R.string.tatrak_update_error_not_release_build
            apkPackage != ourPackage -> R.string.tatrak_update_error_package
            apkSigners.isEmpty() || apkSigners != installedSigners || pinned !in apkSigners -> R.string.tatrak_update_error_signature
            apkVersion <= installedVersion -> R.string.tatrak_update_error_version
            else -> null
        }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Copies exactly [size] bytes; more or fewer deletes [file] and fails. */
    suspend fun copy(input: InputStream, file: File, size: Long, progress: (Float) -> Unit) {
        var done = 0L
        try {
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    done += n
                    if (done > size) throw UpdateException(R.string.tatrak_update_error_size)
                    out.write(buffer, 0, n)
                    progress(done.toFloat() / size)
                }
            }
            if (done != size) throw UpdateException(R.string.tatrak_update_error_size)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /** The stored newer release the user has not put off with "Later"; a pre-release only while betas are on. */
    fun pending(context: Context): Release? {
        val prefs = prefs(context)
        val release = Release.fromJson(prefs.getString(RELEASE, null)) ?: return null
        return release.takeIf {
            isNewer(it.version, BuildConfig.VERSION_NAME) &&
                it.version != prefs.getString(DISMISSED, null) &&
                (prefs.getBoolean(PRERELEASE, false) || !isPreRelease(it.version))
        }
    }

    fun dismiss(context: Context, version: String) = prefs(context).edit().putString(DISMISSED, version).apply()

    /** "Beta versions" changed: forget the stored release and the last check, so the next one runs in the new channel. */
    fun channelChanged(context: Context) = prefs(context).edit().remove(RELEASE).remove(CHECKED_AT).apply()

    /** The automatic check: on, not under Tor, at most once a day; failures wait for the next day. */
    fun checkIfDue(context: Context) {
        val app = context.applicationContext
        val prefs = prefs(app)
        if (!prefs.getBoolean(CHECK, true) || AppSettings(app).isUseTor) return
        if (System.currentTimeMillis() - prefs.getLong(CHECKED_AT, 0) < DAY_MS) return
        prefs.edit().putLong(CHECKED_AT, System.currentTimeMillis()).apply()
        HttpConnectionManager.EXECUTOR.execute { runCatching { check(app) } }
    }

    private fun client(context: Context) =
        // https only, also behind GitHub's redirect to its object storage
        HttpConnectionManager.okHttpClient(context).newBuilder().followSslRedirects(false).build()

    /** Blocking: the latest release (or pre-release, with betas on) if it is newer than this app, also stored for the chat list's notice. */
    fun check(context: Context): Release? {
        if (AppSettings(context).isUseTor) throw UpdateException(R.string.tatrak_update_error_tor)
        val betas = prefs(context).getBoolean(PRERELEASE, false)
        val request = Request.Builder().url(if (betas) RELEASES else LATEST).header("Accept", "application/vnd.github+json").build()
        val json =
            try {
                client(context).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.string()
                }
            } catch (e: IOException) {
                throw UpdateException(R.string.tatrak_update_error_network, e)
            }
        val abis = Build.SUPPORTED_ABIS.toList()
        val release = if (betas) newest(json, abis, BuildConfig.VERSION_NAME) else parse(json, abis)
        prefs(context).edit().putLong(CHECKED_AT, System.currentTimeMillis()).apply()
        if (release == null || !isNewer(release.version, BuildConfig.VERSION_NAME)) return null
        prefs(context).edit().putString(RELEASE, release.toJson()).apply()
        return release
    }

    fun apkFile(context: Context) = File(context.cacheDir, "tatrak-update.apk")

    /** Call on an IO dispatcher; cancelling the coroutine stops it and deletes the file. */
    suspend fun download(context: Context, release: Release, progress: (Float) -> Unit): File {
        if (AppSettings(context).isUseTor) throw UpdateException(R.string.tatrak_update_error_tor)
        if (!acceptUrl(release.url)) throw UpdateException(R.string.tatrak_update_error_release)
        val file = apkFile(context)
        try {
            client(context).newCall(Request.Builder().url(release.url).build()).execute().use { response ->
                if (!response.isSuccessful || !response.request.url.isHttps) throw IOException("HTTP ${response.code}")
                copy(response.body.byteStream(), file, release.size, progress)
            }
        } catch (e: IOException) {
            file.delete()
            throw UpdateException(R.string.tatrak_update_error_network, e)
        }
        return file
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28) info?.signingInfo?.apkContentsSigners else info?.signatures
        return certificates.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private val signatureFlag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun installed(context: Context) = context.packageManager.getPackageInfo(context.packageName, signatureFlag)

    /** Whether this install is release-signed and so can take updates (debug and benchmark builds are not). */
    fun canInstall(context: Context) = RELEASE_CERT in signers(installed(context))

    /** Throws with the reason, after deleting [file], unless the APK passes [refusal]. */
    fun verify(context: Context, file: File) {
        val apk = context.packageManager.getPackageArchiveInfo(file.path, signatureFlag)
        val installed = installed(context)
        val reason =
            refusal(
                apk?.packageName,
                context.packageName,
                signers(apk),
                signers(installed),
                apk?.let(PackageInfoCompat::getLongVersionCode) ?: 0,
                PackageInfoCompat.getLongVersionCode(installed),
            )
        if (reason != null) {
            file.delete()
            throw UpdateException(reason)
        }
    }

    /** Verifies [file], hands it to the system installer and deletes it; the result comes to [UpdateActivity]. */
    fun install(context: Context, file: File) {
        try {
            verify(context, file)
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("tatrak.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = Intent(context, UpdateActivity::class.java).setAction(UpdateActivity.ACTION_STATUS)
                // mutable: the installer adds its status extras; the intent is explicit
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getActivity(context, 0, status, flags).intentSender)
            }
        } catch (e: IOException) {
            throw UpdateException(R.string.tatrak_update_failed, e)
        } catch (e: SecurityException) {
            // the installer refuses the session (a device policy, a revoked install permission): show it, don't crash
            throw UpdateException(R.string.tatrak_update_failed, e)
        } finally {
            file.delete()
        }
    }
}
