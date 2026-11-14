package am.cyberhayq.chat.storage

import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * [Durable]'s system calls for JVM unit tests, with a crash switch: set [stopBefore] to "sync",
 * "rename" or "fsync" (or one exact step, such as "fsync attachments") and the next such step
 * throws instead of running. [steps] records what ran.
 */
class JvmDurable : Durable.Platform {
    var stopBefore: String? = null
    val steps = ArrayList<String>()

    private fun step(name: String, detail: String) {
        if (stopBefore == name || stopBefore == "$name $detail") throw IOException("stopped before $name")
        steps += "$name $detail"
    }

    override fun sync(stream: FileOutputStream) {
        step("sync", "")
        stream.fd.sync()
    }

    override fun rename(from: String, to: String) {
        step("rename", Paths.get(to).fileName.toString())
        Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.ATOMIC_MOVE)
    }

    override fun fsync(path: String) {
        step("fsync", Paths.get(path).fileName.toString())
        FileChannel.open(Paths.get(path), StandardOpenOption.READ).use { it.force(true) } // a missing path throws
    }

    companion object {
        /** Installs a fresh platform; call [uninstall] in the test's `@After`. */
        fun install() = JvmDurable().also { Durable.platform = it }
        private val original = Durable.platform
        fun uninstall() { Durable.platform = original }
    }
}
