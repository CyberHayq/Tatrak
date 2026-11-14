package am.cyberhayq.chat.storage

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** fsync helpers shared by the database storage and the encrypted media store. */
object Durable {
    /**
     * The system calls behind the helpers. JVM unit tests put their own here: Robolectric's `Os`
     * neither renames nor opens a folder, and a test stops at a chosen step to act out a crash.
     */
    internal interface Platform {
        fun sync(stream: FileOutputStream)
        fun rename(from: String, to: String)
        fun fsync(path: String)
    }

    @Volatile internal var platform: Platform = Posix

    /**
     * Writes [bytes] to a temporary sibling, syncs it, renames it over [file] and syncs the folder.
     * The sibling has one fixed name: two writers of one [file] must be serialized by the caller.
     * An exception can come after the rename, when [file] already has the new content.
     */
    @JvmStatic fun durableWrite(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { it.write(bytes); platform.sync(it) }
        platform.rename(temp.path, file.path)
        syncDirectory(checkNotNull(file.parentFile))
    }

    /** fsync of an existing file; a missing file is an error, it is never created. */
    @JvmStatic fun syncFile(file: File) = platform.fsync(file.path)

    @JvmStatic fun syncDirectory(directory: File) = platform.fsync(directory.path)

    /** fsync of a file that is still open for writing. */
    @JvmStatic fun sync(stream: FileOutputStream) = platform.sync(stream)

    /** Atomic rename inside one filesystem; an existing [to] is replaced. */
    @JvmStatic fun rename(from: File, to: File) {
        try { platform.rename(from.path, to.path) } catch (e: ErrnoException) { throw IOException(e) }
    }

    private object Posix : Platform {
        override fun sync(stream: FileOutputStream) = stream.fd.sync()

        override fun rename(from: String, to: String) = Os.rename(from, to)

        override fun fsync(path: String) {
            val fd = Os.open(path, OsConstants.O_RDONLY, 0) // no O_CREAT: syncing never makes a file
            try { Os.fsync(fd) }
            catch (e: Exception) {
                // keep the fsync error: a failing close must not replace it
                try { Os.close(fd) } catch (closing: Exception) { e.addSuppressed(closing) }
                throw e
            }
            Os.close(fd)
        }
    }
}
