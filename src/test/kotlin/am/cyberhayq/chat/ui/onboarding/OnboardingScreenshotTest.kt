package am.cyberhayq.chat.ui.onboarding

import am.cyberhayq.chat.ui.theme.Accent
import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.ActivityEasyInviteBinding
import eu.siacs.conversations.databinding.ActivityEditAccountBinding
import eu.siacs.conversations.databinding.ActivityImportBackupBinding
import eu.siacs.conversations.databinding.ActivityMagicCreateBinding
import eu.siacs.conversations.databinding.ActivityPickServerBinding
import eu.siacs.conversations.databinding.ActivityWelcomeBinding
import eu.siacs.conversations.ui.adapter.BackupFileAdapter
import eu.siacs.conversations.utils.BackupFile
import eu.siacs.conversations.utils.BackupFileHeader
import eu.siacs.conversations.xmpp.Jid
import java.io.DataOutputStream
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The onboarding and account screens restyled in XML only. Each layout is inflated with the app
 * theme and the overlays BaseActivity applies (Tatrak neutrals, optional Slate and Sea, accent),
 * with the views set the way the activities set them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingScreenshotTest {

    private fun activity(accent: Accent = Accent.BLUE, @StyleRes neutrals: Int? = null): Activity {
        // AppCompat, so <Button> inflates as MaterialButton as in the app; the theme is set before
        // onCreate, as BaseActivity does, so the window background follows the overlays too
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_Conversations3)
        activity.theme.applyStyle(R.style.ThemeOverlay_Tatrak, true)
        neutrals?.let { activity.theme.applyStyle(it, true) }
        activity.theme.applyStyle(accent.overlay, true)
        controller.setup()
        return activity
    }

    private fun <T : ViewBinding> show(activity: Activity, binding: T): T {
        activity.setContentView(binding.root)
        return binding
    }

    private fun capture(binding: ViewBinding, name: String) =
        binding.root.captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun welcome(activity: Activity) = show(activity, ActivityWelcomeBinding.inflate(activity.layoutInflater))

    @Test
    fun welcomeLight() = capture(welcome(activity()), "onboarding_welcome")

    @Test
    @Config(qualifiers = "+night")
    fun welcomeDark() = capture(welcome(activity()), "onboarding_welcome_dark")

    @Test
    @Config(qualifiers = "+night")
    fun welcomeEmberDark() = capture(welcome(activity(Accent.EMBER)), "onboarding_welcome_ember_dark")

    @Test
    fun welcomeSlateLight() =
        capture(welcome(activity(Accent.SEA, R.style.ThemeOverlay_Tatrak_SlateSea)), "onboarding_welcome_slate")

    @Test
    @Config(qualifiers = "+night")
    fun welcomeSlateElegantDark() =
        capture(
            welcome(activity(Accent.SEA, R.style.ThemeOverlay_Tatrak_SlateSea_Elegant)),
            "onboarding_welcome_slate_elegant_dark",
        )

    @Test
    fun pickServer() {
        val a = activity()
        capture(show(a, ActivityPickServerBinding.inflate(a.layoutInflater)), "onboarding_pick_server")
    }

    private fun magicCreate(a: Activity): ActivityMagicCreateBinding {
        val b = show(a, ActivityMagicCreateBinding.inflate(a.layoutInflater))
        b.username.setText("ani")
        b.fullJid.text = a.getString(R.string.your_full_jid_will_be, "ani@conversations.im")
        b.fullJid.visibility = View.VISIBLE
        return b
    }

    @Test
    fun magicCreateLight() = capture(magicCreate(activity()), "onboarding_magic_create")

    @Test
    @Config(qualifiers = "+night")
    fun magicCreateDark() = capture(magicCreate(activity()), "onboarding_magic_create_dark")

    @Test
    fun invite() {
        val a = activity()
        val b = show(a, ActivityEasyInviteBinding.inflate(a.layoutInflater))
        b.inProgress.visibility = View.GONE
        b.invite.visibility = View.VISIBLE
        b.tapToShare.text = a.getString(R.string.tap_share_button_send_invite, "tatrak.am")
        // stand-in for the generated QR code
        b.qrCode.setImageBitmap(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) })
        capture(b, "onboarding_invite")
    }

    /** Login form as the activity shows it in init mode: no avatar, no host / port, "Next". */
    private fun login(a: Activity): ActivityEditAccountBinding {
        val b = show(a, ActivityEditAccountBinding.inflate(a.layoutInflater))
        b.avater.visibility = View.GONE
        b.accountRegisterNew.visibility = View.GONE
        b.namePort.visibility = View.GONE
        b.accountJid.setText("ani@tatrak.am")
        b.accountPassword.setText("secret")
        b.saveButton.setText(R.string.next)
        b.saveButton.isEnabled = true
        return b
    }

    @Test
    fun loginLight() = capture(login(activity()), "onboarding_login")

    @Test
    @Config(qualifiers = "+night")
    fun loginDarkWithError() {
        val b = login(activity())
        b.accountPasswordLayout.isErrorEnabled = true
        b.accountPasswordLayout.error = "Wrong password"
        b.namePort.visibility = View.VISIBLE
        b.hostname.setText("10.0.2.2")
        b.port.setText("5222")
        b.accountRegisterNew.visibility = View.VISIBLE
        capture(b, "onboarding_login_dark")
    }

    /** Account details: avatar, connection stats, keys and other devices visible. */
    private fun details(a: Activity): ActivityEditAccountBinding {
        val b = show(a, ActivityEditAccountBinding.inflate(a.layoutInflater))
        b.avater.setImageResource(R.drawable.ic_person_24dp)
        b.accountRegisterNew.visibility = View.GONE
        b.namePort.visibility = View.GONE
        b.accountJid.setText("ani@tatrak.am")
        b.accountJid.isEnabled = false
        b.accountPassword.setText("secret")
        b.accountPassword.isEnabled = false
        b.stats.visibility = View.VISIBLE
        b.sessionEst.text = "5 minutes ago"
        b.serverInfoPep.text = "available"
        b.yourName.text = "Ani"
        b.axolotlFingerprint.text = "05a1b2c3 d4e5f607 18293a4b 5c6d7e8f"
        b.ownFingerprintDesc.setText(R.string.omemo_fingerprint)
        b.pgpFingerprintBox.visibility = View.GONE
        b.osOptimization.visibility = View.VISIBLE
        b.saveButton.setText(R.string.save)
        b.saveButton.isEnabled = false
        return b
    }

    @Test
    fun accountDetailsLight() = capture(details(activity()), "onboarding_account_details")

    @Test
    @Config(qualifiers = "+night")
    fun accountDetailsDark() = capture(details(activity()), "onboarding_account_details_dark")

    /** Backup list as the activity fills it: real adapter, backup headers written to temp files. */
    private fun importBackup(a: Activity): ActivityImportBackupBinding {
        val b = show(a, ActivityImportBackupBinding.inflate(a.layoutInflater))
        val files = listOf("ani@tatrak.am", "work@example.org").map { jid ->
            val file = File.createTempFile("backup", ".ceb", a.cacheDir)
            DataOutputStream(file.outputStream()).use {
                BackupFileHeader("Tatrak", Jid.of(jid), 1_762_000_000_000L, ByteArray(12), ByteArray(16)).write(it)
            }
            BackupFile.read(a, Uri.fromFile(file))
        }
        val adapter = BackupFileAdapter()
        b.list.adapter = adapter
        adapter.setFiles(files)
        shadowOf(Looper.getMainLooper()).idle()
        return b
    }

    @Test
    fun importBackupLight() = capture(importBackup(activity()), "onboarding_import_backup")

    @Test
    @Config(qualifiers = "+night")
    fun importBackupDark() = capture(importBackup(activity()), "onboarding_import_backup_dark")
}
