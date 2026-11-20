package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.avatar.ProfilePictureScreen
import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tatrak: the Compose screen of [ViewProfilePictureActivity]. The activity still decodes the
 * stored avatar in `onStart`; this class shows what it decoded.
 */
internal class ProfilePictureCompose(private val activity: ViewProfilePictureActivity) {

    private var name by mutableStateOf("")
    private var picture by mutableStateOf<Bitmap?>(null)

    fun attach(classic: View) {
        activity.composeOver(classic) { ProfilePictureScreen(name, picture, activity::finish) }
    }

    fun show(name: String?, picture: Bitmap?) {
        this.name = name.orEmpty()
        this.picture = picture
    }
}
