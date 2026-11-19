package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.entities.ReadByMarker
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import im.conversations.android.xmpp.model.muc.Affiliation
import im.conversations.android.xmpp.model.muc.Role
import im.conversations.android.xmpp.model.reactions.Restrictions
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Tatrak: group read markers give the classic screen's "… has read up to this point" rows and the info sheet's readers. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ReadBySnapshotTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val room = Jid.of("room@muc.example.com")

    private fun group(private: Boolean): Pair<Conversation, MucOptions> {
        lateinit var options: MucOptions
        val conversation = object : Conversation(
            "g1", "Room", null, "a1", room, 0, Conversation.STATUS_AVAILABLE,
            Conversational.MODE_MULTI, Conversation.Attributes.parse(null),
        ) {
            override fun getNextEncryption() = Message.ENCRYPTION_NONE
            override fun getMucOptions() = options
            override fun getBookmark() = null // needs the connection
        }.apply { account = Account(Jid.of("tester@example.com"), "secret") }
        // the room's features (service discovery) and the members' contacts (roster) need the connection
        options = object : MucOptions(conversation) {
            override fun isPrivateAndNonAnonymous() = private
            override fun getFeatures() = emptyList<String>()
            override fun hasFeature(feature: String) = false
            override fun getReactionsRestrictions() = Restrictions(null, null)
        }
        for (nick in listOf("Anya", "Boris", "Vika")) {
            val user = object : MucOptions.User(options, room.withResource(nick), Jid.of("${nick.lowercase()}@example.com"), null, Role.PARTICIPANT, Affiliation.MEMBER, emptySet()) {
                override fun getContact() = null
            }
            options.updateUser(user)
        }
        return conversation to options
    }

    private fun marker(nick: String) = ReadByMarker(null, room.withResource(nick), Jid.of("${nick.lowercase()}@example.com"))

    private fun message(conversation: Conversation, uuid: String, status: Int, vararg readBy: String, from: String? = null) =
        Message(conversation, "hello", Message.ENCRYPTION_NONE, status).apply {
            this.uuid = uuid
            setTime(1_000L)
            if (from != null) {
                counterpart = room.withResource(from)
                trueCounterpart = Jid.of("${from.lowercase()}@example.com")
            }
            readBy.forEach { addReadByMarker(marker(it)) }
        }

    private fun items(conversation: Conversation, vararg messages: Message): List<ChatItem> {
        val params = SnapshotParams(TextPalette(0, 0, 0, 0, 0), null, 48, true, 0, false, MentionTracker())
        return ChatSnapshot.items(context, XmppConnectionService(), conversation, params, messages.toList())
    }

    @Test
    fun rowsAndReadersFollowTheMarkers() {
        val (conversation, _) = group(private = true)
        val items =
            items(
                conversation,
                message(conversation, "m1", Message.STATUS_SEND_RECEIVED, "Anya", "Vika"),
                message(conversation, "m2", Message.STATUS_SEND_RECEIVED, "Boris"),
                // Vika wrote later: the classic screen leaves her out of the row above, the sheet keeps her
                message(conversation, "m3", Message.STATUS_RECEIVED, from = "Vika"),
            )
        // newest first, each row right below its message
        assertEquals(listOf("m3", "read-m2", "m2", "read-m1", "m1"), items.filter { it !is DateItem }.map { it.key })
        val rows = items.filterIsInstance<ServiceItem>().associate { it.key to it.text }
        assertEquals("Boris has read up to this point", rows["read-m2"])
        assertEquals("Anya has read up to this point", rows["read-m1"])
        val messages = items.filterIsInstance<MessageItem>().associateBy { it.key }
        assertEquals(listOf("Boris"), messages.getValue("m2").readBy)
        assertEquals(setOf("Boris", "Anya", "Vika"), messages.getValue("m1").readBy.toSet())
        assertEquals(emptyList<String>(), messages.getValue("m3").readBy)
        // a row splits the bubble run
        assertEquals(GroupPosition.SINGLE, messages.getValue("m2").position)
    }

    /** A private message to one member and a failed send get no readers from later public markers. */
    @Test
    fun privateMessageHasNoReaders() {
        val (conversation, _) = group(private = true)
        val items =
            items(
                conversation,
                message(conversation, "p1", Message.STATUS_SEND).apply { type = Message.TYPE_PRIVATE },
                // a failed send is not read either
                message(conversation, "f1", Message.STATUS_SEND_FAILED),
                message(conversation, "m1", Message.STATUS_SEND_RECEIVED, "Anya"),
            )
        val messages = items.filterIsInstance<MessageItem>().associateBy { it.key }
        assertEquals(emptyList<String>(), messages.getValue("p1").readBy)
        assertEquals(emptyList<String>(), messages.getValue("f1").readBy)
        assertEquals(listOf("Anya"), messages.getValue("m1").readBy)
    }

    @Test
    fun nothingInAnAnonymousOrPublicGroup() {
        val (conversation, _) = group(private = false)
        val items = items(conversation, message(conversation, "m1", Message.STATUS_SEND_RECEIVED, "Anya"))
        assertEquals(emptyList<ServiceItem>(), items.filterIsInstance<ServiceItem>())
        assertEquals(emptyList<String>(), (items.first { it.key == "m1" } as MessageItem).readBy)
    }
}
