package am.cyberhayq.chat.ui.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import eu.siacs.conversations.R
import eu.siacs.conversations.ui.MessageSearchResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps [VoiceSession]'s playback going in the background (issue #7, step 2): a `mediaPlayback` foreground service
 * with a private `MediaSession` and its notification, both showing only "Voice message" and the app name. Started by
 * the session after a play from a visible screen; stops itself when the session's `active` ends. Main thread only.
 */
class VoicePlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal lateinit var session: MediaSession
    private var lastStartId = 0
    private var foreground = false
    /** What the posted notification shows: playing and the message its content intent opens. */
    private var shown: Pair<Boolean, String?>? = null

    // other apps that control the session (lock screen, Bluetooth, the headset button) get transport actions only
    internal val callback =
        object : MediaSession.Callback() {
            // Android 16+ may demote a service paused for long: back to the foreground first, then play (onStartCommand);
            // a refused start leaves the message paused
            override fun onPlay() {
                if (!VoiceSession.playing()) VoiceSession.requestService(ACTION_PLAY)
            }

            override fun onPause() = VoiceSession.pause()

            override fun onStop() = VoiceSession.close()

            override fun onSeekTo(pos: Long) = VoiceSession.seekTo(pos)

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == ACTION_CLOSE) VoiceSession.close()
            }
        }

    override fun onCreate() {
        super.onCreate()
        // does nothing when the channel exists
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.tatrak_voice_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        session = MediaSession(this, "tatrak-voice").apply { setCallback(callback) }
        scope.launch { VoiceSession.active.collect { if (!it && lastStartId != 0) stop() } }
        scope.launch { VoiceSession.changes.collect { if (foreground) refresh() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // first on every start, whatever follows: Android ends the app when a started service does not call it
        val bar = updateSession()
        shown = key(bar)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(bar), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!VoiceSession.active.value) {
            stop()
            return START_NOT_STICKY
        }
        foreground = true
        VoiceSession.serviceRunning = true
        session.isActive = true
        when (intent?.action) {
            // in the foreground again by now: focus denied leaves the message paused
            ACTION_TOGGLE -> if (VoiceSession.playing()) VoiceSession.pause() else VoiceSession.resume()
            ACTION_PLAY -> VoiceSession.resume()
            ACTION_CLOSE -> VoiceSession.close()
        }
        return START_NOT_STICKY
    }

    // the user swiped the app away: a paused message goes, a playing one plays on (as monocles)
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (VoiceSession.active.value && !VoiceSession.playing()) VoiceSession.close()
    }

    override fun onDestroy() {
        if (foreground) VoiceSession.serviceRunning = false // destroyed without stop()
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stop() {
        // a start queued meanwhile keeps the service, and in the foreground: its onStartCommand decides
        if (!stopSelfResult(lastStartId)) return
        foreground = false
        VoiceSession.serviceRunning = false
        session.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // the session follows every change (its position too); the notification only what it shows
    private fun refresh() {
        val bar = updateSession()
        if (key(bar) == shown) return
        shown = key(bar)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(bar))
    }

    private fun key(bar: VoiceBarState?) = (bar?.playing == true) to bar?.messageUuid

    /** Sets the session's state and metadata from the bar; returns the bar. */
    private fun updateSession(): VoiceBarState? {
        val bar = VoiceSession.bar.value
        val playing = bar?.playing == true
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    bar?.positionMs?.toLong() ?: 0L,
                    bar?.speed ?: 1f,
                    SystemClock.elapsedRealtime(),
                )
                // Android 13+ builds the media controls from these; the headset button needs PLAY_PAUSE
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(ACTION_CLOSE, getString(R.string.tatrak_voice_close), R.drawable.ic_close_24dp).build()
                )
                .build()
        )
        val title = getString(R.string.tatrak_voice_message)
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, bar?.durationMs?.toLong() ?: 0L)
                .build()
        )
        return bar
    }

    private fun notification(bar: VoiceBarState?): Notification {
        val playing = bar?.playing == true
        val title = getString(R.string.tatrak_voice_message)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val builder =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_play_arrow_24dp)
                .setContentTitle(title)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC) // nothing in it names the chat or the sender
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
                .addAction(action(if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp, if (playing) R.string.pause_audio else R.string.play_audio, ACTION_TOGGLE))
                .addAction(action(R.drawable.ic_close_24dp, R.string.tatrak_voice_close, ACTION_CLOSE))
        // FLAG_UPDATE_CURRENT: the intent follows the playing message
        if (bar != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(this, 0, MessageSearchResults.openAtIntent(this, bar.conversationUuid, bar.messageUuid), flags)
            )
        }
        return builder.build()
    }

    private fun action(icon: Int, label: Int, action: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            getString(label),
            PendingIntent.getService(
                this,
                0,
                Intent(this, VoicePlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    companion object {
        /** Next to `DatabaseStorage`'s 7314; clear of `NotificationService`'s and the backup workers' ids. */
        const val NOTIFICATION_ID = 7315
        const val CHANNEL = "tatrak-voice"
        const val ACTION_TOGGLE = "am.cyberhayq.chat.voice.TOGGLE"
        const val ACTION_CLOSE = "am.cyberhayq.chat.voice.CLOSE"
        /** A play from the media session, sent through a foreground start so the service is in the foreground first. */
        const val ACTION_PLAY = "am.cyberhayq.chat.voice.PLAY"
    }
}
