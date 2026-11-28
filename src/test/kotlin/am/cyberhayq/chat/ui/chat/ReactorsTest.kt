package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.entities.Reaction
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import im.conversations.android.xmpp.model.muc.Affiliation
import im.conversations.android.xmpp.model.muc.Role
import im.conversations.android.xmpp.model.reactions.Restrictions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Tatrak: the rows and tabs of the "who reacted" sheet (issue #10). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ReactorsTest {

    private val room = Jid.of("room@muc.example.com")
    private val account = Account(Jid.of("tester@example.com"), "secret")

    /** [contactNames]: nicks whose current holder is a roster contact with that display name. */
    private fun group(contactNames: Map<String, String> = emptyMap()): Conversation {
        lateinit var options: MucOptions
        val conversation = object : Conversation(
            "g1", "Room", null, "a1", room, 0, Conversation.STATUS_AVAILABLE,
            Conversational.MODE_MULTI, Conversation.Attributes.parse(null),
        ) {
            override fun getNextEncryption() = Message.ENCRYPTION_NONE
            override fun getMucOptions() = options
            override fun getBookmark() = null // needs the connection
        }.apply { account = this@ReactorsTest.account }
        // the room's features (service discovery) and the members' contacts (roster) need the connection
        options = object : MucOptions(conversation) {
            override fun isPrivateAndNonAnonymous() = true
            override fun getFeatures() = emptyList<String>()
            override fun hasFeature(feature: String) = false
            override fun getReactionsRestrictions() = Restrictions(null, null)
        }
        for (nick in listOf("Anya", "Boris", "me")) {
            val user = object : MucOptions.User(options, room.withResource(nick), Jid.of("${nick.lowercase()}@example.com"), "occ-${nick.lowercase()}", Role.PARTICIPANT, Affiliation.MEMBER, emptySet()) {
                override fun getContact() =
                    contactNames[nick]?.let { name ->
                        object : Contact(Jid.of("${nick.lowercase()}@example.com")) {
                            override fun getDisplayName() = name
                        }
                    }
            }
            options.updateUser(user)
        }
        return conversation
    }

    private fun oneToOne(): Conversation {
        val contact = object : Contact(Jid.of("narek@example.com")) {
            override fun getDisplayName() = "Narek"
        }
        return object : Conversation(
            "s1", "Narek", null, "a1", Jid.of("narek@example.com"), 0, Conversation.STATUS_AVAILABLE,
            Conversational.MODE_SINGLE, Conversation.Attributes.parse(null),
        ) {
            override fun getNextEncryption() = Message.ENCRYPTION_NONE
            override fun getContact() = contact
        }.apply { account = this@ReactorsTest.account }
    }

    private fun reaction(emoji: String, nick: String, received: Boolean = true) =
        Reaction(emoji, received, room.withResource(nick), Jid.of("${nick.lowercase()}@example.com"), "occ-${nick.lowercase()}")

    private fun message(conversation: Conversation, vararg reactions: Reaction) =
        Message(conversation, "hi", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply { setReactions(reactions.toList()) }

    private fun row(who: String, emoji: String) = ReactorRow(who, who, emoji, false, true, 0, "")

    @Test
    fun groupRowsAreDeduplicatedAndOnlyOthersAreTappable() {
        val message = message(
            group(),
            reaction("👍", "Anya"),
            reaction("❤", "Boris"), // heart without VS16
            reaction("❤️", "Boris"), // the same emoji with VS16: one row
            reaction("👍", "me", received = false),
        )
        val rows = reactorRows(message, "You")
        assertEquals(listOf("Anya", "Boris", "You"), rows.map { it.name })
        assertEquals(listOf("👍", "❤️", "👍"), rows.map { it.emoji })
        assertEquals(listOf(true, true, false), rows.map { it.tappable })
        assertEquals(listOf(false, false, true), rows.map { it.mine })
    }

    @Test
    fun memberWhoLeftKeepsTheNickOfTheReaction() {
        val message = message(group(), Reaction("😂", true, room.withResource("Gone"), null, "occ-gone"))
        assertEquals("Gone", reactorRows(message, "You").single().name)
    }

    @Test
    fun oneToOneIsYouAndTheContactAndNothingIsTappable() {
        val conversation = oneToOne()
        val peer = Jid.of("narek@example.com")
        val message = message(
            conversation,
            Reaction("👍", true, peer, null, null),
            Reaction("👍", false, account.jid, null, null),
        )
        val rows = reactorRows(message, "You")
        assertEquals(listOf("Narek", "You"), rows.map { it.name })
        assertEquals(listOf(false, false), rows.map { it.tappable })
        assertEquals(listOf("peer", "self"), rows.map { it.who })
    }

    /** A private message stores only room/nick (no occupant id, no real JID): the nick then, whoever holds it now. */
    @Test
    fun privateMessageInAGroupIsNotTappableAndKeepsTheStoredNick() {
        // "Anya" is now somebody else, a roster contact named differently
        val message =
            message(group(mapOf("Anya" to "Anna K.")), Reaction("👍", true, room.withResource("Anya"), null, null))
                .apply { type = Message.TYPE_PRIVATE }
        val row = reactorRows(message, "You").single()
        assertEquals("peer", row.who)
        assertEquals(false, row.tappable)
        assertEquals("Anya", row.name)
        // the same reaction in the public room is looked up
        message.type = Message.TYPE_TEXT
        assertEquals("Anna K.", reactorRows(message, "You").single().name)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun item(conversation: Conversation, vararg reactions: Reaction): MessageItem {
        val params = SnapshotParams(TextPalette(0, 0, 0, 0, 0), null, 48, true, 0, false, MentionTracker())
        val message = message(conversation, *reactions).apply { uuid = "m1"; setTime(1_000L) }
        return ChatSnapshot.items(context, XmppConnectionService(), conversation, params, listOf(message)).filterIsInstance<MessageItem>().single()
    }

    /** Anya takes back 👍 and Boris adds it: the chip is the same, the item is not (an open sheet must follow). */
    @Test
    fun reactorSwapKeepingTheChipsChangesTheItem() {
        val conversation = group()
        val anya = item(conversation, reaction("👍", "Anya"))
        val boris = item(conversation, reaction("👍", "Boris"))
        assertEquals(anya.reactions, boris.reactions)
        assertNotEquals(anya, boris)
        assertEquals(anya, item(conversation, reaction("👍", "Anya")))
        assertEquals(listOf("Anya"), anya.reactors.map { it.name })
    }

    /** The same occupant, renamed (here: the nick's holder is now a contact with another name): the item carries the new name. */
    @Test
    fun renamedReactorChangesTheItem() {
        val before = item(group(), reaction("👍", "Anya"))
        val after = item(group(mapOf("Anya" to "Anna K.")), reaction("👍", "Anya"))
        assertEquals(before.reactions, after.reactions)
        assertEquals(listOf("Anna K."), after.reactors.map { it.name })
        assertNotEquals(before, after)
    }

    /** Two people who left with the same nick and occupant ids of equal String hashes ("Aa" and "BB"): still different items. */
    @Test
    fun swapWithEqualHashesChangesTheItem() {
        assertEquals("occ-Aa".hashCode(), "occ-BB".hashCode())
        val conversation = group()
        val one = item(conversation, Reaction("👍", true, room.withResource("Gone"), null, "occ-Aa"))
        val two = item(conversation, Reaction("👍", true, room.withResource("Gone"), null, "occ-BB"))
        assertNotEquals(one, two)
    }

    /** A received group private message without occupant id or real JID: no message avatar (it would be the nick's holder now). */
    @Test
    fun privateMessageAvatarNeedsAStableSender() {
        val pm = message(group()).apply { type = Message.TYPE_PRIVATE; counterpart = room.withResource("Anya") }
        assertFalse(privateReactorHasMessageAvatar(pm))
        pm.occupantId = "occ-anya"
        assertTrue(privateReactorHasMessageAvatar(pm))
        pm.occupantId = null
        pm.trueCounterpart = Jid.of("anya@example.com")
        assertTrue(privateReactorHasMessageAvatar(pm))
        // own message: the account's avatar, not the message's
        val sent = Message(group(), "hi", Message.ENCRYPTION_NONE, Message.STATUS_SEND).apply { type = Message.TYPE_PRIVATE; occupantId = "occ-me" }
        assertFalse(privateReactorHasMessageAvatar(sent))
    }

    @Test
    fun tabsCountRowsInChipOrderAndVanishForOneEmoji() {
        val rows = listOf(row("a", "👍"), row("b", "❤️"), row("c", "👍"))
        assertEquals(listOf(null to 3, "👍" to 2, "❤️" to 1), reactorTabs(rows, listOf("👍", "❤️")))
        assertEquals(emptyList<Pair<String?, Int>>(), reactorTabs(rows.filter { it.emoji == "👍" }, listOf("👍")))
    }
}
