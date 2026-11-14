package am.cyberhayq.chat.ui.backup

import android.app.Application
import android.view.LayoutInflater
import android.widget.RadioGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.viewinterop.AndroidView
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** The restore dialog's content (`dialog_enter_password`) with its three OMEMO modes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RestorePasswordDialogScreenshotTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun omemoModes() {
        compose.setContent {
            AndroidView(factory = { context ->
                val themed = ContextThemeWrapper(context, R.style.Theme_Conversations3)
                LayoutInflater.from(themed).inflate(R.layout.dialog_enter_password, null).apply {
                    // as ImportBackupActivity does with the default mode
                    findViewById<RadioGroup>(R.id.omemo_import).check(R.id.omemo_full)
                }
            })
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/restore_password_dialog.png")
    }
}
