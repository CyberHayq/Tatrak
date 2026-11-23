package eu.siacs.conversations.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.LocaleList
import am.cyberhayq.chat.ui.settings.languageContext
import androidx.core.app.AppLocalesStorageHelper
import androidx.core.os.LocaleListCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.AppSettings
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.ReviewFlowsTest
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Notifications in the in-app language with an English system: API 30 builds them in Russian (the service itself keeps
 * the system language there); API 35 changes nothing (the system applies the per-app language to the whole process).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [30],
    qualifiers = "en-rUS",
    shadows = [ReviewFlowsTest.NoAxolotlStore::class],
)
class NotificationLanguageTest {

    private val app get() = ApplicationProvider.getApplicationContext<ReviewFlowsTest.TestApp>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var service: ReviewFlowsTest.TestService

    @Before
    fun start() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        service = ReviewFlowsTest.TestService(app)
    }

    private fun channelNames() = listOf("messages", "foreground", "incoming_calls_channel#0").map { manager.getNotificationChannel(it).name.toString() }

    private fun posted(summary: Boolean) = shadowOf(manager).allNotifications.single { (it.flags and Notification.FLAG_GROUP_SUMMARY != 0) == summary }

    /** One received message: the summary's title and the chat's actions, then the chat's text with message text hidden. */
    private fun messageTexts(): List<String> {
        val chat = service.chat(service.a, Jid.of("friend@example.com"))
        val message = Message(chat, "hello", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED)
        chat.add(message)
        service.notificationService.push(message)
        val texts = listOf(posted(summary = true).extras.getCharSequence(Notification.EXTRA_TITLE).toString()) +
            posted(summary = false).actions.map { it.title.toString() }
        // hidden text: "1 message" from the app's resources instead of the body
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(AppSettings.HIDE_NOTIFICATION_TEXT, true).commit()
        service.notificationService.updateNotification()
        return texts + posted(summary = false).extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    }

    @Test
    fun storedChoiceAtServiceStartRenamesChannelsInPlace() {
        service.notificationService.initializeChannels()
        assertEquals(listOf("Messages", "Foreground service", "Incoming calls"), channelNames())
        val ids = manager.notificationChannels.map { it.id }.toSet()
        AppLocalesStorageHelper.persistLocales(app, "ru")
        service.notificationService.initializeChannels()
        assertEquals(listOf("Сообщения", "Процесс переднего плана", "Входящие вызовы"), channelNames())
        assertEquals("no channel deleted or added", ids, manager.notificationChannels.map { it.id }.toSet())
        assertEquals(listOf("1 непрочитанная беседа", "Прочитано", "Ответить", "1 сообщение"), messageTexts())
    }

    /** The Language list's call into a running service: a choice just made, not yet in AppCompat's file. */
    @Test
    fun choiceInTheRunningService() {
        service.notificationService.initializeChannels()
        service.notificationService.setLanguage(LocaleListCompat.forLanguageTags("ru"))
        assertEquals(listOf("Сообщения", "Процесс переднего плана", "Входящие вызовы"), channelNames())
        assertEquals(listOf("1 непрочитанная беседа", "Прочитано", "Ответить", "1 сообщение"), messageTexts())
        service.notificationService.setLanguage(LocaleListCompat.getEmptyLocaleList())
        assertEquals(listOf("Messages", "Foreground service", "Incoming calls"), channelNames())
    }

    /** Groups and descriptions follow; a channel the user changed keeps the change (only its labels are re-sent). */
    @Test
    fun relabelsGroupsAndDescriptionsAndKeepsUserSettings() {
        service.notificationService.initializeChannels()
        val call = manager.getNotificationChannel("incoming_calls_channel#0")
        call.importance = NotificationManager.IMPORTANCE_LOW
        call.setSound(null, null)
        manager.createNotificationChannel(call)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel("incoming_calls_channel#0").importance)
        service.notificationService.setLanguage(LocaleListCompat.forLanguageTags("ru"))
        val ru = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale("ru")) })
        assertEquals(
            listOf("Информация о статусе", "Сообщения", "Вызовы"),
            listOf("status", "chats", "calls").map { id -> manager.notificationChannelGroups.single { it.id == id }.name.toString() },
        )
        assertEquals(ru.getString(R.string.silent_messages_channel_description), manager.getNotificationChannel("silent_messages").description)
        assertEquals(ru.getString(R.string.error_channel_description), manager.getNotificationChannel("error").description)
        val renamed = manager.getNotificationChannel("incoming_calls_channel#0")
        assertEquals("Входящие вызовы", renamed.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, renamed.importance)
        assertNull(renamed.sound)
    }

    @Test
    fun failedDeliveryTexts() {
        service.notificationService.initializeChannels()
        service.notificationService.setLanguage(LocaleListCompat.forLanguageTags("ru"))
        val chat = service.chat(service.a, Jid.of("friend@example.com"))
        val message = Message(chat, "hello", Message.ENCRYPTION_NONE, Message.STATUS_SEND_FAILED)
        chat.add(message)
        service.notificationService.pushFailedDelivery(message)
        assertEquals("Невозможно доставить сообщение", posted(summary = false).extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val summary = posted(summary = true).extras
        assertEquals("Недоставленные сообщения", summary.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Невозможно доставить сообщения", summary.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    /** A system string missing in the chosen language falls back to the system language, as in the UI. */
    @Test
    fun systemLocalesFollowTheChosenOne() {
        val context = languageContext(app, LocaleListCompat.forLanguageTags("ru"))
        assertEquals(LocaleList.forLanguageTags("ru,en-US"), context.resources.configuration.locales)
    }

    @Test
    @Config(sdk = [35])
    fun android13LeavesTheServiceContext() {
        service.notificationService.initializeChannels()
        service.notificationService.setLanguage(LocaleListCompat.forLanguageTags("ru"))
        assertEquals(listOf("Messages", "Foreground service", "Incoming calls"), channelNames())
        assertEquals(listOf("1 unread chat", "Mark as read", "Reply", "1 message"), messageTexts())
    }
}
