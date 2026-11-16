package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.content.Context
import android.database.MatrixCursor
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus
import eu.siacs.conversations.crypto.axolotl.SQLiteAxolotlStore
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Tatrak: the menu's "Sent / Received" lines come only for received messages with a known time;
 * its key line names the encryption and, for OMEMO, the key.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ReceivedAtSnapshotTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // without a connection there is no roster, so the chat's encryption is given here
    private val conversation = object : Conversation(
        "c1", "Alice", null, "a1", Jid.of("alice@example.com"), 0, Conversation.STATUS_AVAILABLE,
        Conversational.MODE_SINGLE, Conversation.Attributes.parse(null),
    ) {
        override fun getNextEncryption() = Message.ENCRYPTION_NONE
    }.apply { account = Account(Jid.of("tester@example.com"), "secret") }

    private fun message(uuid: String, status: Int, received: Long?, encryption: Int = Message.ENCRYPTION_NONE): Message =
        Message(conversation, "hello", encryption, status).apply {
            this.uuid = uuid
            setTime(1_000L)
            timeReceived = received
        }

    private fun items(vararg messages: Message): Map<String, MessageItem> {
        val params = SnapshotParams(TextPalette(0, 0, 0, 0, 0), null, 48, true, 0, false, MentionTracker())
        return ChatSnapshot.items(context, XmppConnectionService(), conversation, params, messages.toList())
            .filterIsInstance<MessageItem>().associateBy { it.key }
    }

    @Test
    fun onlyReceivedMessagesWithAKnownTimeGetTheLines() {
        val items =
            items(
                message("in", Message.STATUS_RECEIVED, 5_000L),
                message("unknown", Message.STATUS_RECEIVED, null),
                message("own", Message.STATUS_SEND_DISPLAYED, 5_000L),
            )
        assertEquals(5_000L, items.getValue("in").receivedAt)
        assertEquals(1_000L, items.getValue("in").sentAt)
        assertNull(items.getValue("unknown").receivedAt)
        assertNull(items.getValue("own").receivedAt)
    }

    // an OMEMO message needs the account's connection (Account.getAxolotlService), which a unit test lacks
    @Test
    fun keyLineNamesTheEncryption() {
        val items =
            items(
                message("plain", Message.STATUS_RECEIVED, null),
                message("pgp", Message.STATUS_SEND, null, Message.ENCRYPTION_DECRYPTED),
                message("call", Message.STATUS_RECEIVED, null).apply { type = Message.TYPE_RTP_SESSION },
            )
        assertEquals(KeyInfo(R.string.encryption_choice_unencrypted), items.getValue("plain").encryptionKey)
        assertEquals(KeyInfo(R.string.encryption_choice_pgp), items.getValue("pgp").encryptionKey)
        assertNull(items["call"]?.encryptionKey)
    }

    @Test
    fun trustLabelFollowsTheKeyStatus() {
        val undecided = FingerprintStatus.createActiveUndecided()
        assertEquals(R.string.tatrak_chat_key_this_device, keyTrustLabel(undecided, own = true))
        assertEquals(R.string.tatrak_chat_key_unknown, keyTrustLabel(null, own = false))
        assertEquals(R.string.tatrak_chat_key_verified, keyTrustLabel(FingerprintStatus.createActiveVerified(false), own = false))
        assertEquals(R.string.tatrak_chat_key_verified, keyTrustLabel(FingerprintStatus.createActiveVerified(true), own = false))
        assertEquals(R.string.tatrak_chat_key_trusted, keyTrustLabel(FingerprintStatus.createActiveTrusted(), own = false))
        assertEquals(R.string.tatrak_chat_key_untrusted, keyTrustLabel(FingerprintStatus.createActive(false), own = false))
        assertEquals(R.string.tatrak_chat_key_untrusted, keyTrustLabel(undecided, own = false))
        // a key that was verified and later distrusted is untrusted, an inactive one keeps its trust
        assertEquals(R.string.tatrak_chat_key_untrusted, keyTrustLabel(FingerprintStatus.createActiveVerified(false).toUntrusted(), own = false))
        assertEquals(R.string.tatrak_chat_key_trusted, keyTrustLabel(FingerprintStatus.createActiveTrusted().toInactive(), own = false))
        // COMPROMISED has no factory: only the trust store's rows carry it
        val row =
            MatrixCursor(arrayOf(SQLiteAxolotlStore.TRUST, SQLiteAxolotlStore.ACTIVE, SQLiteAxolotlStore.LAST_ACTIVATION)).apply {
                addRow(arrayOf<Any>("COMPROMISED", 0, 0L))
                moveToFirst()
            }
        assertEquals(R.string.tatrak_chat_key_compromised, keyTrustLabel(FingerprintStatus.fromCursor(row), own = false))
    }

    @Test
    fun fingerprintShowsAsTwoLinesOfFourGroups() {
        val hex = "3f2a9c41b7e0d58a61c4f9e20d7b3a689e15c2f7a4d860b3c7f21e9558b0a4d6"
        assertEquals(
            "3f2a9c41 b7e0d58a 61c4f9e2 0d7b3a68\n9e15c2f7 a4d860b3 c7f21e95 58b0a4d6",
            fingerprintLines("05$hex"),
        )
    }

    @Test
    fun theYearShowsOnlyWhenNotTheCurrentOne() {
        fun at(time: LocalDateTime) = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = LocalDateTime.now().withNano(0)
        val thisYear = receiptTime(context, Locale.US, at(now))
        val lastYear = receiptTime(context, Locale.US, at(now.minusYears(1)))
        assertFalse(thisYear, thisYear.contains(now.year.toString()))
        assertTrue(lastYear, lastYear.contains(now.minusYears(1).year.toString()))
        // seconds are shown
        assertTrue(thisYear, thisYear.contains(":%02d".format(now.second)))
    }
}
