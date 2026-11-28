package am.cyberhayq.chat.commands

import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.Jid
import im.conversations.android.xmpp.model.stanza.Iq
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CommandSessionTest {

    private val sent = ArrayList<Iq>()
    private val answers = ArrayDeque<suspend () -> Element>()
    private val links = ArrayList<String>()
    private var closed = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun session(timeoutMs: Long = COMMAND_TIMEOUT_MS) =
        CommandSession(Jid.of("10.0.2.2"), "add", "Add user", scope, { iq -> sent += iq; answers.removeFirst()() }, { links += it }, { closed++ }, timeoutMs)

    private fun answer(xml: Element) = answers.addLast { xml }

    private fun Iq.command() = findChild("command", Namespace.COMMANDS)!!

    private fun Iq.form() = command().findChild("x", Namespace.DATA)

    private fun Iq.value(v: String) = form()!!.children.first { it.getAttribute("var") == v }.children.map { it.content }

    private val form = result(cmd(EXECUTING, ADD_USER_FORM + """<actions execute="complete"><prev/><complete/></actions>"""))

    private fun started(): CommandSession {
        answer(form)
        return session().also { it.start() }
    }

    private fun CommandSession.fill() {
        val stage = stage!!
        set(stage.fields.first { it.key == "accountjid" }, listOf("new@10.0.2.2"))
        set(stage.fields.first { it.key == "password" }, listOf("pw"))
    }

    @Test
    fun startExecutesTheNode() {
        val session = started()
        val command = sent.single().command()
        assertEquals("add", command.getAttribute("node"))
        assertEquals("execute", command.getAttribute("action"))
        assertNull(command.getAttribute("sessionid"))
        assertEquals("s1", session.stage!!.sessionId)
        assertFalse(session.executing)
    }

    @Test
    fun requiredFieldsAreCheckedBeforeProceeding() {
        val session = started()
        session.execute(CommandActions.COMPLETE)
        assertEquals(setOf("accountjid", "password"), session.missing)
        assertEquals(1, sent.size)
        session.fill()
        assertTrue(session.missing.isEmpty())
        answer(result(cmd(COMPLETED, """<note>Done</note>""")))
        session.execute(CommandActions.COMPLETE)
        val iq = sent.last()
        assertEquals("complete", iq.command().getAttribute("action"))
        assertEquals("s1", iq.command().getAttribute("sessionid"))
        assertEquals("10.0.2.2", iq.to.toString())
        assertEquals("submit", iq.form()!!.getAttribute("type"))
        assertEquals(listOf("new@10.0.2.2"), iq.value("accountjid"))
        assertEquals(listOf("http://jabber.org/protocol/admin"), iq.value("FORM_TYPE"))
        // booleans as true / false (the server sent "1")
        assertEquals(listOf("true"), iq.value("welcome"))
        assertEquals(listOf("one", "two"), iq.value("notes"))
    }

    @Test
    fun prevIsNotValidatedAndCarriesNoForm() {
        val session = started()
        answer(form)
        session.execute(CommandActions.PREV)
        assertEquals("prev", sent.last().command().getAttribute("action"))
        assertNull(sent.last().form())
    }

    @Test
    fun cancelCarriesNoFormAndIsSentOnce() {
        // Cheogram 5c20049d, 816e33d9
        val session = started()
        session.fill()
        answer(result(cmd(CANCELED, "")))
        session.cancel()
        session.cancel()
        session.execute(CommandActions.CANCEL)
        assertEquals(2, sent.size)
        assertEquals("cancel", sent.last().command().getAttribute("action"))
        assertNull(sent.last().form())
        assertEquals(1, closed)
        assertTrue(session.closed)
    }

    @Test
    fun aFinishedSessionIsNotCanceled() {
        answer(result(cmd(COMPLETED, """<note>Done</note>""")))
        val session = session().also { it.start() }
        session.cancel()
        assertEquals(1, sent.size)
        assertEquals(1, closed)
    }

    @Test
    fun pressesWhileARequestRunsAreIgnoredButCancel() {
        // Cheogram 908d9b46
        val session = started()
        session.fill()
        val pending = CompletableDeferred<Element>()
        answers.addLast { pending.await() }
        session.execute(CommandActions.COMPLETE)
        assertTrue(session.executing)
        session.execute(CommandActions.COMPLETE)
        session.execute(CommandActions.PREV)
        assertEquals(2, sent.size)
        answer(result(cmd(CANCELED, "")))
        session.cancel()
        assertEquals("cancel", sent.last().command().getAttribute("action"))
        assertTrue(session.closed)
        // the late answer changes nothing
        pending.complete(form)
        assertEquals(1, closed)
    }

    @Test
    fun aStageWithoutAFormProceedsWithoutOne() {
        // Cheogram 5f311288
        answer(result(cmd(EXECUTING, """<note>Step one</note><actions><next/></actions>""")))
        val session = session().also { it.start() }
        answer(result(cmd(COMPLETED, "<note>ok</note>")))
        session.execute(CommandActions.NEXT)
        assertEquals("next", sent.last().command().getAttribute("action"))
        assertNull(sent.last().form())
    }

    @Test
    fun customActionSetsTheFieldAndExecutes() {
        answer(
            result(
                cmd(
                    EXECUTING,
                    """<x xmlns="jabber:x:data" type="form"><field var="$ACTIONS_FIELD" type="list-single"><option label="Approve"><value>approve</value></option></field><field var="reason"/></x>""",
                )
            )
        )
        val session = session().also { it.start() }
        answer(result(cmd(COMPLETED, "<note>ok</note>")))
        session.execute("approve")
        assertEquals("execute", sent.last().command().getAttribute("action"))
        assertEquals(listOf("approve"), sent.last().value(ACTIONS_FIELD))
    }

    @Test
    fun gridPickShowsLoadingAtOnceAndExecutes() {
        // Cheogram a39da8ce
        answer(result(cmd(EXECUTING, """<x xmlns="jabber:x:data" type="form"><field var="pick" type="list-single"><option><value>a</value></option><option><value>b</value></option></field></x>""")))
        val session = session().also { it.start() }
        val pending = CompletableDeferred<Element>()
        answers.addLast { pending.await() }
        session.pick("b")
        assertTrue(session.loading)
        assertEquals("execute", sent.last().command().getAttribute("action"))
        assertEquals(listOf("b"), sent.last().value("pick"))
        pending.complete(result(cmd(COMPLETED, "<note>ok</note>")))
        assertFalse(session.loading)
        assertEquals("ok", session.stage!!.notes.single().text)
    }

    @Test
    fun spinnerAfterHalfASecond() {
        answer(form)
        val session = session()
        answers.clear()
        answers.addLast { awaitCancellation() }
        session.start()
        assertFalse(session.loading)
        Thread.sleep(LOADING_DELAY_MS + 400)
        assertTrue(session.loading)
        assertFalse(session.slow)
        session.close()
    }

    @Test
    fun timeoutShowsAnErrorWithClose() {
        answers.addLast { awaitCancellation() }
        val session = session(timeoutMs = 50)
        session.start()
        Thread.sleep(400)
        assertEquals(CommandError(null, null, timeout = true), session.stage!!.error)
        assertEquals(listOf("close"), session.stage!!.actions.map { it.id })
        assertFalse(session.executing)
        session.execute(CommandActions.CLOSE)
        assertEquals(1, closed)
    }

    @Test
    fun droppedConnectionShowsAnError() {
        answers.addLast { throw IOException("gone") }
        val session = session().also { it.start() }
        assertEquals(CommandError(null, null), session.stage!!.error)
    }

    @Test
    fun completedWithNothingClosesTheSession() {
        // Cheogram 2717177e
        answer(result(cmd(COMPLETED, "")))
        val session = session().also { it.start() }
        assertTrue(session.closed)
        assertEquals(1, closed)
    }

    @Test
    fun xmppLinkOpensAndCloses() {
        answer(result(cmd(COMPLETED, """<x xmlns="jabber:x:oob"><url>xmpp:room@conference.10.0.2.2?join</url></x>""")))
        session().start()
        assertEquals(listOf("xmpp:room@conference.10.0.2.2?join"), links)
        assertEquals(1, closed)
    }

    @Test
    fun httpsLinkOpensOnceAndStays() {
        answer(result(cmd(COMPLETED, """<x xmlns="jabber:x:oob"><url>https://example.org</url></x>""")))
        val session = session().also { it.start() }
        assertEquals(listOf("https://example.org"), links)
        assertFalse(session.closed)
        assertEquals("https://example.org", session.stage!!.link)
    }

    @Test
    fun aCompletedStageClosesOnAnyButPrev() {
        answer(result(cmd(COMPLETED, ADD_USER_FORM + "<actions><prev/></actions>")))
        val session = session().also { it.start() }
        assertEquals(listOf("prev", "close"), session.stage!!.actions.map { it.id })
        answer(form)
        session.execute(CommandActions.PREV)
        assertEquals("prev", sent.last().command().getAttribute("action"))
        assertFalse(session.closed)
    }

    @Test
    fun editsWhileARequestRunsAreIgnored() {
        val session = started()
        session.fill()
        answers.addLast { CompletableDeferred<Element>().await() }
        session.execute(CommandActions.COMPLETE)
        session.set(session.stage!!.fields.first { it.key == "password" }, listOf("other"))
        assertEquals(listOf("pw"), session.values(session.stage!!.fields.first { it.key == "password" }))
    }

    @Test
    fun aReplyWithoutASessionIdKeepsTheEstablishedOne() {
        val session = started()
        answer(result(cmd(EXECUTING, ADD_USER_FORM, session = "")))
        session.fill()
        session.execute(CommandActions.COMPLETE)
        answers.addLast { CompletableDeferred<Element>().await() }
        session.fill()
        session.execute(CommandActions.COMPLETE)
        assertEquals("s1", sent[2].command().getAttribute("sessionid"))
        answer(result(cmd(CANCELED, "")))
        session.cancel()
        assertEquals("s1", sent.last().command().getAttribute("sessionid"))
        assertEquals("cancel", sent.last().command().getAttribute("action"))
    }

    private fun error(condition: String, ns: String = "urn:ietf:params:xml:ns:xmpp-stanzas") =
        iq("""<iq xmlns="jabber:client" type="error" from="10.0.2.2" id="1"><error type="modify"><bad-request xmlns="urn:ietf:params:xml:ns:xmpp-stanzas"/><$condition xmlns="$ns"/><text xmlns="urn:ietf:params:xml:ns:xmpp-stanzas">Nope</text></error></iq>""")

    @Test
    fun aRejectedSubmissionKeepsTheFormAndItsEdits() {
        val session = started()
        val generation = session.generation
        session.fill()
        answer(error("bad-payload", "http://jabber.org/protocol/commands"))
        session.execute(CommandActions.COMPLETE)
        assertEquals("Nope", session.stage!!.error?.text)
        assertEquals(EXECUTING, session.stage!!.status)
        assertEquals(listOf("pw"), session.values(session.stage!!.fields.first { it.key == "password" }))
        assertEquals(generation, session.generation)
        assertTrue(session.edited)
        // the next answer replaces the error
        answer(form)
        session.execute(CommandActions.COMPLETE)
        assertNull(session.stage!!.error)
        assertEquals(generation + 1, session.generation)
    }

    @Test
    fun anExpiredSessionEndsTheFormAndIsNotCanceled() {
        val session = started()
        session.fill()
        answer(error("session-expired", "http://jabber.org/protocol/commands"))
        session.execute(CommandActions.COMPLETE)
        assertEquals(listOf("close"), session.stage!!.actions.map { it.id })
        session.execute(CommandActions.CLOSE)
        assertEquals(2, sent.size)
        assertEquals(1, closed)
    }

    @Test
    fun closeAfterATimeoutCancelsTheEstablishedSession() {
        answer(form)
        val session = session(timeoutMs = 50).also { it.start() }
        session.fill()
        answers.addLast { awaitCancellation() }
        session.execute(CommandActions.COMPLETE)
        Thread.sleep(400)
        assertTrue(session.stage!!.error!!.timeout)
        answer(result(cmd(CANCELED, "")))
        session.execute(CommandActions.CLOSE)
        assertEquals("cancel", sent.last().command().getAttribute("action"))
        assertEquals("s1", sent.last().command().getAttribute("sessionid"))
        assertEquals(1, closed)
    }

    @Test
    fun multiSelectKeepsTheOfferedOrder() {
        val field = CommandStage.parse(result(cmd(EXECUTING, """<x xmlns="jabber:x:data" type="form"><field var="m" type="list-multi"><option><value>a</value></option><option><value>b</value></option><option><value>c</value></option></field></x>"""))).fields.single()
        var values = am.cyberhayq.chat.ui.commands.multiValues(field, listOf("x"), "c", true)
        values = am.cyberhayq.chat.ui.commands.multiValues(field, values, "a", true)
        assertEquals(listOf("a", "c", "x"), values)
        assertEquals(listOf("c", "x"), am.cyberhayq.chat.ui.commands.multiValues(field, values, "a", false))
    }

    @Test
    fun editsResetOnANewStage() {
        val session = started()
        session.fill()
        assertTrue(session.edited)
        answer(form)
        session.execute(CommandActions.COMPLETE)
        assertFalse(session.edited)
        assertEquals(emptyList<String>(), session.values(session.stage!!.fields.first { it.key == "accountjid" }))
    }
}
