package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.R
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
        val dnd = contactStatusRow(context, "Ani", dnd = true, contactTime = null, noRecentMessages = true)!!
        assertEquals("Ani has paused their notifications", dnd.text.text)
        assertEquals(R.drawable.ic_notifications_paused_24dp, dnd.icon)
        assertNull(contactStatusRow(context, "Ani", dnd = true, contactTime = null, noRecentMessages = false))
        val local = ZonedDateTime.now().offset
        val away = if (local == ZoneOffset.ofHours(9)) ZoneOffset.ofHours(-5) else ZoneOffset.ofHours(9)
        val night = ZonedDateTime.of(2026, 11, 19, 23, 30, 0, 0, away)
        val row = contactStatusRow(context, "Ani", dnd = false, contactTime = night, noRecentMessages = true)!!
        val text = row.text.text
        assertTrue(text, text.startsWith("It’s ") && text.endsWith(" for Ani"))
        assertEquals(R.drawable.ic_schedule_24dp, row.icon)
        // only the time is bold, as in the classic row
        val bold = row.text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertTrue(Regex("\\d{2}:\\d{2}").matches(text.substring(bold.start, bold.end)))
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

    /** A one-to-one chat with someone not in the roster. */
    private fun chat(address: Jid): Conversation =
        object : Conversation(
            "c1", "Stranger", null, "a1", address, 0, Conversation.STATUS_AVAILABLE,
            Conversational.MODE_SINGLE, Conversation.Attributes.parse(null),
        ) {
            override fun isWithStranger() = true // the roster needs the connection
        }.apply { account = Account(Jid.of("tester@example.com"), "secret") }

    /** ConversationFragment.updateSnackBar: group error, then OpenPGP, then the stranger; later checks are not asked. */
    @Test
    fun laterBannersFollowTheClassicOrder() {
        val asked = ArrayList<String>()
        fun banner(groupOffline: Boolean, pgp: Boolean, stranger: Boolean, connected: Boolean = true) =
            laterBanner(
                groupOffline,
                { asked += "group"; null },
                { asked += "pgp"; pgp },
                { asked += "stranger"; stranger },
                { asked += "connected"; connected },
            )
        assertEquals(ChatBanner.PGP_PENDING, banner(groupOffline = false, pgp = true, stranger = true))
        assertEquals(listOf("pgp"), asked)
        asked.clear()
        assertEquals(ChatBanner.STRANGER, banner(groupOffline = false, pgp = false, stranger = true, connected = false))
        assertEquals(listOf("pgp", "stranger"), asked)
        asked.clear()
        assertEquals(ChatBanner.CONNECTING, banner(groupOffline = false, pgp = false, stranger = false, connected = false))
        assertEquals(listOf("pgp", "stranger", "connected"), asked)
        asked.clear()
        // a group that is not joined ends the chain even without an error banner, as the classic one
        assertNull(banner(groupOffline = true, pgp = true, stranger = true))
        assertEquals(listOf("group"), asked)
    }

    /** showBlockSubmenu: a server address offers only "Block entire domain", as the classic menu. */
    @Test
    fun domainStrangerHasNoBlockStranger() {
        assertEquals(BlockMenu(blockContact = false, reject = false), ChatSnapshot.blockMenu(chat(Jid.of("example.org"))))
        assertEquals(BlockMenu(blockContact = true, reject = false), ChatSnapshot.blockMenu(chat(Jid.of("stranger@example.org"))))
    }
}
