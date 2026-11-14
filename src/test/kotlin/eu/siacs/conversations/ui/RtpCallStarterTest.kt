package eu.siacs.conversations.ui

import android.Manifest
import android.app.Application
import eu.siacs.conversations.R
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
class RtpCallStarterTest {

    @Test
    fun deniedBluetoothStillCalls() {
        val result = mapOf(Manifest.permission.RECORD_AUDIO to true, Manifest.permission.BLUETOOTH_CONNECT to false)
        assertNull(RtpCallStarter.deniedMessage(result))
    }

    @Test
    fun deniedCameraNamesTheCamera() {
        val result = mapOf(Manifest.permission.RECORD_AUDIO to true, Manifest.permission.CAMERA to false)
        assertEquals(R.string.no_camera_permission, RtpCallStarter.deniedMessage(result))
    }

    @Test
    fun deniedMicrophoneNamesTheMicrophone() {
        val result = mapOf(Manifest.permission.RECORD_AUDIO to false, Manifest.permission.BLUETOOTH_CONNECT to true)
        assertEquals(R.string.no_microphone_permission, RtpCallStarter.deniedMessage(result))
    }
}
