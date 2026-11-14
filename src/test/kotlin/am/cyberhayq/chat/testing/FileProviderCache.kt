package am.cyberhayq.chat.testing

import androidx.core.content.FileProvider

/**
 * FileProvider keeps its roots in a static cache keyed by authority; in one test JVM the first
 * Robolectric test to share a file fixes the roots for every later one. Tests that share files
 * clear it first.
 */
object FileProviderCache {
    @JvmStatic
    fun clear() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        val cache = field.get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }
}
