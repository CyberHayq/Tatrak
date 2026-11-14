package eu.siacs.conversations.ui

import android.app.Application
import android.net.Uri
import eu.siacs.conversations.ui.util.Attachment
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class MediaViewerPagesTest {
    @Test fun ordersAndFiltersPages() {
        val newest = Attachment.of(UUID.fromString("00000000-0000-0000-0000-000000000001"), File("/new.jpg"), "image/jpeg")
        val oldest = Attachment.of(UUID.fromString("00000000-0000-0000-0000-000000000002"), File("/old.jpg"), "image/jpeg")
        val constructor = Attachment::class.java.getDeclaredConstructor(UUID::class.java, Uri::class.java, Attachment.Type::class.java, String::class.java)
        constructor.isAccessible = true
        val remote = constructor.newInstance(UUID.fromString("00000000-0000-0000-0000-000000000003"), Uri.parse("content://media/remote"), Attachment.Type.IMAGE, "image/jpeg")
        val all = listOf(newest, remote, oldest)
        val (pages, index) = viewerPages(newest.uuid.toString(), all)!!
        assertEquals(listOf(oldest, newest), pages)
        assertEquals(1, index)
        assertNull(viewerPages("missing", all))
        assertNull(viewerPages(newest.uuid.toString(), listOf(newest, remote)))
    }
}
