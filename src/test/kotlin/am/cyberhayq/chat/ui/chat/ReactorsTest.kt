package am.cyberhayq.chat.ui.chat

import android.app.Application
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Contact
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.entities.Reaction
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

/** Tatrak: the rows and tabs of the "who reacted" sheet (issue #10). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ReactorsTest {

    private val room = Jid.of("room@muc.example.com")
    private val account = Account(Jid.of("tester@example.com"), "secret")

    private fun group(): Conversation {
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
                override fun getContact() = null
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

    @Test
    fun privateMessageInAGroupIsNotTappable() {
        val message = message(group(), reaction("👍", "Anya")).apply { type = Message.TYPE_PRIVATE }
        val row = reactorRows(message, "You").single()
        assertEquals("peer", row.who)
        assertEquals(false, row.tappable)
        assertEquals("Anya", row.name)
    }

    @Test
    fun tabsCountRowsInChipOrderAndVanishForOneEmoji() {
        val rows = listOf(row("a", "👍"), row("b", "❤️"), row("c", "👍"))
        assertEquals(listOf(null to 3, "👍" to 2, "❤️" to 1), reactorTabs(rows, listOf("👍", "❤️")))
        assertEquals(emptyList<Pair<String?, Int>>(), reactorTabs(rows.filter { it.emoji == "👍" }, listOf("👍")))
    }
}
