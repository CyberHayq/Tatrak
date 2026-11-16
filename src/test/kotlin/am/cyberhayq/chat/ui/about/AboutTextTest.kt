package am.cyberhayq.chat.ui.about

import android.app.Application
import android.widget.TextView
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.AboutActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The About page shows Tatrak's header, its source, Conversations' copyright and the licences it must carry. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class AboutTextTest {

    private fun aboutText(): String =
        Robolectric.buildActivity(AboutActivity::class.java).setup().get().findViewById<TextView>(R.id.about).text.toString()

    @Test
    fun english() = check(aboutText(), "Tatrak • Secure by design.")

    @Test
    @Config(qualifiers = "ru")
    fun russian() = check(aboutText(), "Tatrak • безопасность заложена в основу.")

    private fun check(text: String, header: String) {
        assertTrue(text.startsWith(header))
        for (part in listOf(
            "https://github.com/CyberHayq/Tatrak",
            "Daniel Gultsch",
            "GNU General Public License",
            "SQLCipher for Android",
            "Manrope font",
            "Maps by Open Street Map",
        )) {
            assertTrue(part, part in text)
        }
        // the upstream header and source link are replaced, not repeated
        assertTrue("the very last word" !in text && "Download the full source code" !in text)
    }
}
