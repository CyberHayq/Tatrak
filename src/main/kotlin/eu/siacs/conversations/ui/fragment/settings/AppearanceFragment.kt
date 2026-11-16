package eu.siacs.conversations.ui.fragment.settings

import am.cyberhayq.chat.ui.chat.Wallpapers
import am.cyberhayq.chat.ui.settings.AppearanceScreen
import am.cyberhayq.chat.ui.theme.AvatarShape
import am.cyberhayq.chat.ui.theme.NightBackground
import am.cyberhayq.chat.ui.theme.TatrakStyle
import am.cyberhayq.chat.ui.theme.TatrakTheme
import am.cyberhayq.chat.ui.theme.rememberAccent
import am.cyberhayq.chat.ui.theme.rememberStringPreference
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.BaseActivity
import eu.siacs.conversations.ui.XmppActivity
import kotlinx.coroutines.launch

/** Theme mode, accent and the app-wide wallpaper; writes the same settings the classic pages use. */
class AppearanceFragment : Fragment() {

    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            val context = requireContext().applicationContext
            lifecycleScope.launch {
                val spec = Wallpapers.importPhoto(context, uri)
                if (spec == null) {
                    Toast.makeText(context, R.string.tatrak_wallpaper_failed, Toast.LENGTH_SHORT).show()
                } else {
                    setWallpaper(context, spec)
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TatrakTheme {
                    val settings = remember { AppSettings(requireContext()) }
                    val themes = remember {
                        resources.getStringArray(R.array.themes_values).zip(resources.getStringArray(R.array.themes))
                    }
                    val theme = rememberStringPreference(AppSettings.THEME) ?: getString(R.string.theme)
                    var dynamicColors by remember { mutableStateOf(settings.isDynamicColorsDesired) }
                    val style = TatrakStyle.remember()
                    AppearanceScreen(
                        style = style,
                        onStyle = { TatrakStyle.set(requireContext(), it); activity?.recreate() },
                        themes = themes,
                        theme = theme,
                        onTheme = { value ->
                            settings.setTheme(value)
                            (activity as? BaseActivity)?.setDesiredNightMode(settings.desiredNightMode)
                        },
                        dynamicColorsAvailable = DynamicColors.isDynamicColorAvailable(),
                        dynamicColors = dynamicColors,
                        onDynamicColors = { value ->
                            dynamicColors = value
                            settings.setDynamicColors(value)
                            (activity as? BaseActivity)?.setDynamicColors(value)
                        },
                        nightBackground = NightBackground.remember(),
                        onNightBackground = { NightBackground.set(requireContext(), it); activity?.recreate() },
                        accent = rememberAccent(),
                        // recreating recolours this activity's classic toolbar too
                        onAccent = { settings.accent = it.key; activity?.recreate() },
                        wallpaper = rememberStringPreference(AppSettings.WALLPAPER),
                        onWallpaper = { setWallpaper(requireContext().applicationContext, it) },
                        onWallpaperPhoto = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        avatarShape = AvatarShape.remember(style),
                        onAvatarShape = { AvatarShape.set(requireContext(), it) },
                    )
                }
            }
        }

    private fun setWallpaper(context: Context, spec: String) {
        AppSettings(context).wallpaper = spec
        val service = (activity as? XmppActivity)?.xmppConnectionService ?: return
        lifecycleScope.launch { Wallpapers.deleteUnused(context, service) }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().setTitle(R.string.tatrak_appearance)
    }
}
