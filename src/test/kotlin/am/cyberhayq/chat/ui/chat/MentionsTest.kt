package am.cyberhayq.chat.ui.chat

import android.app.Application
import android.text.Selection
import android.text.SpannableStringBuilder
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.services.NotificationService
import eu.siacs.conversations.xmpp.Jid
import kotlin.random.Random
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
class MentionsTest {

    private val known = setOf("anna", "bob", "carl")
    private val knownNicks: (List<String>) -> Boolean = { nicks -> nicks.all { it in known } }

    private fun highlight(text: String, selection: TextRange, nick: String = "dora") =
        TextFieldValue(text, selection).highlightInConference(nick, knownNicks)

    // --- highlightInConference, branch by branch ---

    @Test
    fun emptyTextStartsWithTheAddress() {
        assertEquals(TextFieldValue("dora: ", TextRange(6)), highlight("", TextRange(0)))
    }

    @Test
    fun blankTextGetsTheAddressInFront() {
        // Java trim(): blank text counts as empty wherever the cursor is
        assertEquals(TextFieldValue("dora:   ", TextRange(8)), highlight("  ", TextRange(2)))
    }

    @Test
    fun cursorAtTheStartAddsTheAddressInFront() {
        assertEquals(TextFieldValue("dora: hello", TextRange(6)), highlight("hello", TextRange(0)))
    }

    @Test
    fun afterALineBreakAddressesOnThatLine() {
        assertEquals(TextFieldValue("hi\ndora: ", TextRange(9)), highlight("hi\n", TextRange(3)))
    }

    @Test
    fun knownAddressesGetTheNickAppended() {
        assertEquals(TextFieldValue("anna, bob, dora: ", TextRange(17)), highlight("anna, bob: ", TextRange(11)))
    }

    @Test
    fun unknownAddressIsNotMerged() {
        assertEquals(TextFieldValue("eve: dora ", TextRange(10)), highlight("eve: ", TextRange(5)))
    }

    @Test
    fun insideAWordSpacesAreAddedAround() {
        assertEquals(TextFieldValue("he dora llo", TextRange(8)), highlight("hello", TextRange(2)))
    }

    @Test
    fun atTheEndASpaceFollows() {
        assertEquals(TextFieldValue("hello dora ", TextRange(11)), highlight("hello", TextRange(5)))
    }

    @Test
    fun existingSpacesAreReusedAndSkipped() {
        assertEquals(TextFieldValue("hello dora world", TextRange(11)), highlight("hello world", TextRange(6)))
    }

    @Test
    fun aSelectionMovesWithTheInsertedText() {
        assertEquals(TextFieldValue("he dora llo", TextRange(8, 11)), highlight("hello", TextRange(2, 5)))
    }

    /** The same edits as the classic method on an Android Editable with its selection spans. */
    @Test
    fun matchesTheClassicMethodOnAnEditable() {
        val random = Random(11)
        val pieces = listOf("a", "b", " ", "\n", ":", ",", ": ", ", ", "anna", "bob", "eve", "anna, bob: ", " ", "\t")
        repeat(3000) { run ->
            val text = buildString { repeat(random.nextInt(0, 6)) { append(pieces.random(random)) } }
            val a = random.nextInt(0, text.length + 1)
            val b = if (random.nextBoolean()) a else random.nextInt(0, text.length + 1)
            val expected = classic(text, a, b, "dora")
            assertEquals("run $run: '$text' [$a, $b]", expected, highlight(text, TextRange(a, b)))
        }
    }

    /** ConversationFragment.highlightInConference, line for line, on a SpannableStringBuilder. */
    private fun classic(text: String, selectionStart: Int, selectionEnd: Int, nick: String): TextFieldValue {
        val editable = SpannableStringBuilder(text)
        Selection.setSelection(editable, selectionStart, selectionEnd)
        val oldString = editable.toString().trim { it <= ' ' }
        val pos = Selection.getSelectionStart(editable)
        if (oldString.isEmpty() || pos == 0) {
            editable.insert(0, "$nick: ")
        } else {
            val before = editable[pos - 1]
            val after = if (editable.length > pos) editable[pos] else '\u0000'
            if (before == '\n') {
                editable.insert(pos, "$nick: ")
            } else {
                var merged = false
                if (pos > 2 && editable.subSequence(pos - 2, pos).toString() == ": ") {
                    val nicks = java.util.regex.Pattern.compile(", ").split(editable.subSequence(0, pos - 2).toString()).toList()
                    if (knownNicks(nicks)) {
                        editable.insert(pos - 2, ", $nick")
                        merged = true
                    }
                }
                if (!merged) {
                    editable.insert(
                        pos,
                        (if (Character.isWhitespace(before)) "" else " ") + nick + (if (Character.isWhitespace(after)) "" else " "),
                    )
                    if (Character.isWhitespace(after)) {
                        Selection.setSelection(editable, Selection.getSelectionStart(editable) + 1)
                    }
                }
            }
        }
        return TextFieldValue(
            editable.toString(),
            TextRange(Selection.getSelectionStart(editable), Selection.getSelectionEnd(editable)),
        )
    }

    // --- "@" query and completion ---

    @Test
    fun atTheStartOfTheTextIsAQuery() {
        assertEquals(MentionQuery(0, 3, "an"), mentionQuery(TextFieldValue("@an", TextRange(3))))
    }

    @Test
    fun afterWhitespaceIsAQuery() {
        assertEquals(MentionQuery(3, 4, ""), mentionQuery(TextFieldValue("hi @", TextRange(4))))
        assertEquals(MentionQuery(3, 6, "b"), mentionQuery(TextFieldValue("hi\n@bo", TextRange(5))))
    }

    @Test
    fun notAQuery() {
        assertNull(mentionQuery(TextFieldValue("mail@an", TextRange(7))))
        assertNull(mentionQuery(TextFieldValue("@an ", TextRange(4))))
        assertNull(mentionQuery(TextFieldValue("@an", TextRange(0))))
        assertNull(mentionQuery(TextFieldValue("@an", TextRange(1, 3))))
        assertNull(mentionQuery(TextFieldValue("", TextRange(0))))
    }

    @Test
    fun completionAtTheStartAddressesThePerson() {
        val value = TextFieldValue("@an", TextRange(3))
        assertEquals(TextFieldValue("anna: ", TextRange(6)), value.completeMention(mentionQuery(value)!!, "anna"))
    }

    @Test
    fun completionElsewhereIsTheNickAndASpace() {
        val value = TextFieldValue("hi @an", TextRange(6))
        assertEquals(TextFieldValue("hi anna ", TextRange(8)), value.completeMention(mentionQuery(value)!!, "anna"))
    }

    @Test
    fun completionReplacesTheWholeWordAndReusesTheSpace() {
        val value = TextFieldValue("hi @anxx there", TextRange(6))
        assertEquals(TextFieldValue("hi anna there", TextRange(8)), value.completeMention(mentionQuery(value)!!, "anna"))
        val first = TextFieldValue("@a there", TextRange(2))
        assertEquals(TextFieldValue("anna: there", TextRange(6)), first.completeMention(mentionQuery(first)!!, "anna"))
    }

    // --- pending mentions ---

    private val room =
        Conversation("c", "room", null, "a", Jid.of("room@muc.example.com"), 0, 0, Conversational.MODE_MULTI, Conversation.Attributes())
    private val me = NotificationService.generateNickHighlightPattern("me")

    private fun received(body: String, read: Boolean = false, status: Int = Message.STATUS_RECEIVED, type: Int = Message.TYPE_TEXT) =
        Message(room, body, Message.ENCRYPTION_NONE, status).also {
            it.type = type
            if (!read) it.markUnread()
        }

    @Test
    fun pendingMentionsAreUnreadReceivedMessagesThatHaveTheNick() {
        val old = received("me: read before the chat opened", read = true)
        val mention = received("me: look")
        val own = received("me too", status = Message.STATUS_SEND)
        val part = received("meh, not you")
        val punct = received("thanks, me!")
        val retracted = received("me", type = Message.TYPE_RETRACTED)
        val messages = listOf(old, mention, own, part, punct, retracted)
        assertEquals(listOf(mention.uuid, punct.uuid), MentionTracker().update(messages, me))
        assertEquals(emptyList<String>(), MentionTracker().update(messages, null))
    }

    @Test
    fun aMentionArrivingInAReadChatCountsUntilSeen() {
        val tracker = MentionTracker()
        val history = listOf(received("hello", read = true), received("me: old", read = true))
        assertEquals(emptyList<String>(), tracker.update(history, me))
        // scrolled up into history: the new mention stays unread
        val mention = received("me: are you there?")
        assertEquals(listOf(mention.uuid), tracker.update(history + mention, me))
        // "scroll down" marks everything read, the mention was never on screen: still pending
        mention.markRead()
        assertEquals(listOf(mention.uuid), tracker.update(history + mention, me))
        tracker.markSeen(listOf(mention.uuid))
        assertEquals(setOf(mention.uuid), tracker.seen)
    }

    @Test
    fun noAnchorIsNeeded() {
        // the oldest unread message (the old unread anchor) is gone: later mentions still count
        val anchor = received("first unread")
        val mention = received("me: hi")
        val tracker = MentionTracker()
        assertEquals(listOf(mention.uuid), tracker.update(listOf(anchor, mention), me))
        assertEquals(listOf(mention.uuid), tracker.update(listOf(mention), me))
    }

    @Test
    fun seenMentionsStaySeen() {
        val mention = received("me: hi")
        val tracker = MentionTracker()
        tracker.update(listOf(mention), me)
        tracker.markSeen(listOf(mention.uuid))
        mention.markRead()
        // the next snapshot still lists it; the seen set (kept by the view model) keeps it off the button
        assertEquals(emptyList<String>(), tracker.update(listOf(mention), me).filterNot { it in tracker.seen })
    }
}
