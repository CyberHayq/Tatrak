package am.cyberhayq.chat.ui.call

import android.app.Activity
import android.app.Application
import android.view.View
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.R
import eu.siacs.conversations.databinding.ActivityRtpSessionBinding
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** The restyled XML call screen with the states the activity sets (ringing, in a call). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CallScreenScreenshotTest {

    private fun screen(): ActivityRtpSessionBinding {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Conversations3)
        val binding = ActivityRtpSessionBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        binding.with.text = "Ani Petrosyan"
        binding.contactPhoto.visibility = View.VISIBLE
        binding.contactPhoto.setImageResource(R.drawable.ic_person_24dp)
        binding.verified.visibility = View.GONE
        binding.usingAccount.visibility = View.GONE
        return binding
    }

    @Test
    fun incoming() {
        val b = screen()
        b.tatrakCallState.setText(R.string.rtp_state_incoming_call)
        b.withJid.text = "ani@tatrak.am"
        b.endCall.visibility = View.INVISIBLE
        b.rejectCall.visibility = View.VISIBLE
        b.acceptCall.visibility = View.VISIBLE
        b.root.captureRoboImage("build/outputs/roborazzi/call_incoming.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun connected() {
        val b = screen()
        b.tatrakCallState.setText(R.string.rtp_state_connected)
        b.withJid.visibility = View.GONE
        b.duration.text = "01:23"
        b.duration.visibility = View.VISIBLE
        b.inCallActionLeft.setImageResource(R.drawable.ic_mic_24dp)
        b.inCallActionLeft.visibility = View.VISIBLE
        b.inCallActionRight.setImageResource(R.drawable.ic_volume_off_24dp)
        b.inCallActionRight.visibility = View.VISIBLE
        b.root.captureRoboImage("build/outputs/roborazzi/call_connected.png")
    }
}
