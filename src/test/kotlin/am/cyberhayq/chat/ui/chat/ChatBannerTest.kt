package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.MucOptions
import eu.siacs.conversations.services.XmppConnectionService
import eu.siacs.conversations.xmpp.Jid
import im.conversations.android.xmpp.model.muc.Affiliation
import im.conversations.android.xmpp.model.muc.Role
import im.conversations.android.xmpp.model.reactions.Restrictions
import im.conversations.android.xmpp.model.state.Composing
import im.conversations.android.xmpp.model.state.Paused
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Tatrak: the classic snackbar chain, the status rows and the group typing subtitle without a connection. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35])
class ChatBannerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val room = Jid.of("room@muc.example.com")

    private fun group(status: Int = Conversation.STATUS_AVAILABLE, blocked: Boolean = false): Pair<Conversation, MucOptions> {
        lateinit var options: MucOptions
        val conversation = object : Conversation(
            "g1", "Room", null, "a1", room, 0, status,
            Conversational.MODE_MULTI, Conversation.Attributes.parse(null),
        ) {
            override fun getNextEncryption() = Message.ENCRYPTION_NONE
            override fun getMucOptions() = options
            override fun getBookmark() = null // needs the connection
            override fun isBlocked() = blocked // the roster needs the connection
        }.apply { account = Account(Jid.of("tester@example.com"), "secret") }
        options = object : MucOptions(conversation) {
            override fun isPrivateAndNonAnonymous() = false
            override fun getFeatures() = emptyList<String>()
            override fun hasFeature(feature: String) = false
            override fun getReactionsRestrictions() = Restrictions(null, null)
            override fun getName() = "Room" // service discovery needs the connection
        }
        return conversation to options
    }

    private fun user(options: MucOptions, nick: String) =
        object : MucOptions.User(options, room.withResource(nick), null, null, Role.PARTICIPANT, Affiliation.MEMBER, emptySet()) {
            override fun getContact() = null
        }.also { options.updateUser(it) }

    @Test
    fun bannerFollowsTheClassicOrder() {
        assertNull(ChatSnapshot.banner(group(status = Conversation.STATUS_ARCHIVED).first))
        // not connected: Tatrak's own banner where the classic screen shows none
        assertEquals(ChatBanner.CONNECTING, ChatSnapshot.banner(group().first))
        assertEquals(ChatBanner.BLOCKED, ChatSnapshot.banner(group(blocked = true).first))
        val disabled = group(blocked = true).first.apply { account.setOption(Account.OPTION_DISABLED, true) }
        assertEquals(ChatBanner.ACCOUNT_DISABLED, ChatSnapshot.banner(disabled))
        val loggedOut = group().first.apply { account.setOption(Account.OPTION_SOFT_DISABLED, true) }
        assertEquals(ChatBanner.ACCOUNT_LOGGED_OUT, ChatSnapshot.banner(loggedOut))
    }

    @Test
    fun groupErrorsGiveTheClassicTextAndAction() {
        assertEquals(ChatBanner.SERVER_NOT_FOUND_RETRY, mucBanner(MucOptions.Error.SERVER_NOT_FOUND, receivedMessages = true))
        assertEquals(ChatBanner.SERVER_NOT_FOUND_LEAVE, mucBanner(MucOptions.Error.SERVER_NOT_FOUND, receivedMessages = false))
        assertEquals(ChatBanner.SERVER_TIMEOUT_LEAVE, mucBanner(MucOptions.Error.REMOTE_SERVER_TIMEOUT, false))
        assertEquals(ChatBanner.INVALID_NICK, mucBanner(MucOptions.Error.INVALID_NICK, false))
        assertNull(mucBanner(MucOptions.Error.NONE, true))
        // every error but NONE has a banner
        assertEquals(MucOptions.Error.entries.size - 1, MucOptions.Error.entries.mapNotNull { mucBanner(it, true) }.size)
    }

    @Test
    fun loadMoreRowAsTheClassicCondition() {
        var asked = false
        val query = { asked = true; false }
        assertTrue(loadMoreRow(mam = true, lastClearHistory = 1_000L, emptyWithHistoryLeft = false, query))
        assertFalse(asked) // a cleared history does not ask about a running query
        assertFalse(loadMoreRow(mam = false, lastClearHistory = 1_000L, emptyWithHistoryLeft = true, query))
        assertFalse(loadMoreRow(mam = true, lastClearHistory = 0L, emptyWithHistoryLeft = false, query))
        assertTrue(loadMoreRow(mam = true, lastClearHistory = null, emptyWithHistoryLeft = true, query))
        assertFalse(loadMoreRow(mam = true, lastClearHistory = null, emptyWithHistoryLeft = true) { true })
    }

    @Test
    fun contactStatusRows() {
        assertEquals(
            "Ani has paused their notifications",
            contactStatusRow(context, "Ani", dnd = true, contactTime = null, noRecentMessages = true),
        )
        assertNull(contactStatusRow(context, "Ani", dnd = true, contactTime = null, noRecentMessages = false))
        val local = ZonedDateTime.now().offset
        val away = if (local == ZoneOffset.ofHours(9)) ZoneOffset.ofHours(-5) else ZoneOffset.ofHours(9)
        val night = ZonedDateTime.of(2026, 11, 19, 23, 30, 0, 0, away)
        val row = contactStatusRow(context, "Ani", dnd = false, contactTime = night, noRecentMessages = true)
        assertTrue(row, row!!.startsWith("It’s ") && row.endsWith(" for Ani"))
        assertNull(contactStatusRow(context, "Ani", false, night.withHour(12), true))
        assertNull(contactStatusRow(context, "Ani", false, night.withZoneSameLocal(local), true))
    }

    @Test
    fun groupHeaderNamesWhoIsTyping() {
        val (conversation, options) = group()
        val anya = user(options, "Anya")
        val boris = user(options, "Boris")
        fun header() = ChatSnapshot.header(context, XmppConnectionService(), conversation, 48, true, false)
        assertEquals("2 Participants", header().subtitle)
        assertFalse(header().subtitleActive)
        anya.setChatState(Paused::class.java)
        assertEquals("2 Participants", header().subtitle)
        anya.setChatState(Composing::class.java)
        assertEquals("Anya is typing…", header().subtitle)
        assertTrue(header().subtitleActive)
        boris.setChatState(Composing::class.java)
        // in MucOptions' user order
        assertTrue(header().subtitle in setOf("Anya, Boris are typing…", "Boris, Anya are typing…"))
    }
}
