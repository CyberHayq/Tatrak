package am.cyberhayq.chat.commands

import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xml.Namespace
import im.conversations.android.xml.XmlElementReader
import im.conversations.android.xmpp.model.stanza.Iq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

internal fun iq(xml: String): Iq = XmlElementReader.read(xml, Iq::class.java)

/** A result IQ from the test server with [command] inside. */
internal fun result(command: String) =
    iq("""<iq xmlns="jabber:client" type="result" from="10.0.2.2" id="1">$command</iq>""")

internal fun cmd(status: String, body: String, session: String = "s1") =
    """<command xmlns="http://jabber.org/protocol/commands" node="add" sessionid="$session" status="$status">$body</command>"""

internal const val ADD_USER_FORM =
    """<x xmlns="jabber:x:data" type="form"><title>Adding a User</title><instructions>Fill out this form.</instructions>
<field var="FORM_TYPE" type="hidden"><value>http://jabber.org/protocol/admin</value></field>
<field var="accountjid" type="jid-single" label="The Jabber ID for the account to be added"><required/></field>
<field var="password" type="text-private" label="The password for this account"><required/></field>
<field type="fixed"><value>Optional</value></field>
<field var="notes" type="text-multi" label="Notes"><value>one</value><value>two</value></field>
<field var="welcome" type="boolean" label="Send welcome"><value>1</value></field></x>"""

internal fun Element.ids() = children.map { it.name }

private fun actions(stage: CommandStage) = stage.actions.map { it.id }

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CommandStageTest {

    @Test
    fun actionsAfterTheForm() {
        // Cheogram 18b07510: <actions> after the form is read too
        val stage = CommandStage.parse(result(cmd(EXECUTING, ADD_USER_FORM + """<actions execute="complete"><next/><complete/></actions>""")))
        assertEquals(listOf("cancel", "next", "complete"), actions(stage))
        assertEquals("complete", stage.defaultAction)
        assertEquals("Adding a User", stage.title)
        assertEquals(listOf("Fill out this form."), stage.instructions)
        assertEquals("s1", stage.sessionId)
        assertEquals("10.0.2.2", stage.from.toString())
        assertEquals(listOf("accountjid", "password", "notes", "welcome"), stage.fillable.map { it.key })
        assertTrue(stage.fields.first { it.key == "accountjid" }.required)
        assertEquals(listOf("one", "two"), stage.fields.first { it.key == "notes" }.values)
    }

    @Test
    fun actionsBeforeTheForm() {
        val stage = CommandStage.parse(result(cmd(EXECUTING, """<actions execute="next"><next/></actions>""" + ADD_USER_FORM)))
        assertEquals(listOf("cancel", "next"), actions(stage))
    }

    @Test
    fun executingWithoutActionsGetsExecute() {
        // Cheogram d0871411 (biboumi): something must move on
        val stage = CommandStage.parse(result(cmd(EXECUTING, ADD_USER_FORM)))
        assertEquals(listOf("cancel", "execute"), actions(stage))
    }

    @Test
    fun completedWithNothingCloses() {
        // Cheogram 2717177e
        assertTrue(CommandStage.parse(result(cmd(COMPLETED, ""))).closes)
        val note = CommandStage.parse(result(cmd(COMPLETED, """<note type="info">User added</note>""")))
        assertFalse(note.closes)
        assertEquals(listOf(CommandNote("User added", false)), note.notes)
        assertEquals(listOf("close"), actions(note))
    }

    @Test
    fun submitAndCancelFormsAreIgnored() {
        // Cheogram d963e8cd: biboumi echoes the submitted form
        val stage = CommandStage.parse(result(cmd(COMPLETED, """<x xmlns="jabber:x:data" type="submit"><field var="a"><value>b</value></field></x>""")))
        assertNull(stage.formType)
        assertTrue(stage.fields.isEmpty())
        assertTrue(stage.closes)
    }

    @Test
    fun errorTextThenCondition() {
        val text =
            CommandStage.parse(
                iq(
                    """<iq xmlns="jabber:client" type="error" from="10.0.2.2" id="1"><error type="cancel"><forbidden xmlns="urn:ietf:params:xml:ns:xmpp-stanzas"/><text xmlns="urn:ietf:params:xml:ns:xmpp-stanzas">Not an admin</text></error></iq>"""
                )
            )
        assertEquals(CommandError("Not an admin", "forbidden"), text.error)
        assertEquals(listOf("close"), actions(text))
        val bare =
            CommandStage.parse(
                iq("""<iq xmlns="jabber:client" type="error" from="10.0.2.2" id="1"><error type="cancel"><item-not-found xmlns="urn:ietf:params:xml:ns:xmpp-stanzas"/></error></iq>""")
            )
        assertEquals(CommandError(null, "item-not-found"), bare.error)
    }

    @Test
    fun errorAndNoteLook() {
        val stage = CommandStage.parse(result(cmd(COMPLETED, """<note type="error">Failed</note><note>Done</note>""")))
        assertEquals(listOf(CommandNote("Failed", true), CommandNote("Done", false)), stage.notes)
    }

    @Test
    fun itemCardsTakeReportedLabels() {
        val stage =
            CommandStage.parse(
                result(
                    cmd(
                        COMPLETED,
                        """<x xmlns="jabber:x:data" type="result"><reported><field var="jid" label="User" type="jid-single"/><field var="url" label="Home"><validate xmlns="http://jabber.org/protocol/xdata-validate" datatype="xs:anyURI"/></field></reported>
<item><field var="url"><value>https://example.org</value></field><field var="jid"><value>a@10.0.2.2</value></field></item>
<item><field var="jid"><value>b@10.0.2.2</value></field></item></x>""",
                    )
                )
            )
        assertEquals(2, stage.items.size)
        assertEquals(listOf("User" to listOf("a@10.0.2.2"), "Home" to listOf("https://example.org")), stage.items[0].map { it.title to it.values })
        assertEquals("xs:anyURI", stage.items[0][1].datatype)
        assertEquals(listOf(listOf("b@10.0.2.2"), emptyList()), stage.items[1].map { it.values })
        assertFalse(stage.closes)
        assertEquals(listOf("close"), actions(stage))
    }

    private fun lone(field: String, actions: String = "", status: String = EXECUTING) =
        CommandStage.parse(result(cmd(status, """<x xmlns="jabber:x:data" type="form"><field var="FORM_TYPE" type="hidden"><value>x</value></field>$field</x>$actions""")))

    private fun options(n: Int) = (1..n).joinToString("") { """<option label="Option $it"><value>$it</value></option>""" }

    @Test
    fun gridRule() {
        // a lone list-single with no proceed action becomes buttons, keeping cancel
        val grid = lone("""<field var="pick" type="list-single">${options(3)}</field>""")
        assertEquals("pick", grid.grid?.key)
        assertEquals(listOf("cancel"), actions(grid))
        // Cheogram f16e3ecb: one hidden next still makes a grid; cde4d04c: prev stays
        val next = lone("""<field var="pick" type="list-single">${options(3)}</field>""", "<actions><prev/><next/></actions>")
        assertNotNull(next.grid)
        assertEquals(listOf("cancel", "prev"), actions(next))
        // two proceed actions, 50 options, a range or a boolean with a value: no grid
        assertNull(lone("""<field var="pick" type="list-single">${options(3)}</field>""", "<actions><next/><complete/></actions>").grid)
        assertNull(lone("""<field var="pick" type="list-single">${options(50)}</field>""").grid)
        assertNull(lone("""<field var="pick" type="list-single">${options(3)}<validate xmlns="http://jabber.org/protocol/xdata-validate"><range min="1" max="3"/></validate></field>""").grid)
        assertNull(lone("""<field var="ok" type="boolean"><value>0</value></field>""").grid)
        // a lone boolean without a value: Yes / No
        assertEquals("ok", lone("""<field var="ok" type="boolean"/>""").grid?.key)
        // a second fillable field: no grid
        assertNull(lone("""<field var="pick" type="list-single">${options(3)}</field><field var="t"/>""").grid)
        // an open list takes a value of its own, an empty one has nothing to tap, execute="prev" would go back
        assertNull(lone("""<field var="pick" type="list-single">${options(1)}<validate xmlns="http://jabber.org/protocol/xdata-validate"><open/></validate></field>""").grid)
        assertNull(lone("""<field var="pick" type="list-single"/>""").grid)
        assertNull(lone("""<field var="pick" type="list-single">${options(3)}</field>""", """<actions execute="prev"><prev/><next/></actions>""").grid)
        // custom buttons stay buttons
        val custom = lone("""<field var="$ACTIONS_FIELD" type="list-single"><option><value>go</value></option></field><field var="pick" type="list-single">${options(3)}</field>""")
        assertNull(custom.grid)
        assertEquals(listOf("cancel", "go"), actions(custom))
    }

    @Test
    fun onlyProtocolActionsAndANonBlankSession() {
        val stage = CommandStage.parse(result(cmd(EXECUTING, ADD_USER_FORM + "<actions><next/><execute/><bogus/></actions>", session = " ")))
        assertEquals(listOf("cancel", "next"), actions(stage))
        assertNull(stage.sessionId)
    }

    @Test
    fun sessionGoneErrors() {
        val error = """<iq xmlns="jabber:client" type="error" from="10.0.2.2" id="1"><error type="modify"><bad-request xmlns="urn:ietf:params:xml:ns:xmpp-stanzas"/><bad-sessionid xmlns="http://jabber.org/protocol/commands"/></error></iq>"""
        val gone = CommandStage.parse(iq(error)).error!!
        assertEquals("bad-request", gone.condition)
        assertTrue(gone.gone)
        assertFalse(CommandStage.parse(iq(error.replace("bad-sessionid", "bad-payload"))).error!!.gone)
    }

    @Test
    fun customActionsField() {
        val stage =
            lone(
                """<field var="$ACTIONS_FIELD" type="list-single"><option label="Approve"><value>approve</value></option><option label="Reject"><value>reject</value></option></field><field var="reason"/>""",
                "<actions><next/></actions>",
            )
        assertEquals(listOf("cancel", "approve", "reject"), actions(stage))
        assertEquals("Approve", stage.actions[1].label)
        assertEquals(listOf("reason"), stage.fillable.map { it.key })
    }

    @Test
    fun prevFromAFinishedStageKeepsClose() {
        // Cheogram 0dccd5fc
        val stage = lone("""<field var="t"/>""", "<actions><prev/></actions>", COMPLETED)
        assertEquals(listOf("prev", "close"), actions(stage))
    }

    @Test
    fun outOfBandLinks() {
        val https = CommandStage.parse(result(cmd(COMPLETED, """<x xmlns="jabber:x:oob"><url>https://example.org/pay</url></x>""")))
        assertEquals("https://example.org/pay", https.link)
        assertFalse(https.closes)
        val xmpp = CommandStage.parse(result(cmd(COMPLETED, """<x xmlns="jabber:x:oob"><url>xmpp:room@conference.10.0.2.2?join</url></x>""")))
        assertEquals("xmpp:room@conference.10.0.2.2?join", xmpp.xmppUri)
        assertTrue(xmpp.closes)
        val other = CommandStage.parse(result(cmd(COMPLETED, """<x xmlns="jabber:x:oob"><url>javascript:alert(1)</url></x>""")))
        assertNull(other.link)
        assertNull(other.xmppUri)
        // https only (owner, 2026-11-28)
        assertNull(CommandStage.parse(result(cmd(COMPLETED, """<x xmlns="jabber:x:oob"><url>http://example.org/pay</url></x>"""))).link)
    }

    @Test
    fun fieldTypesAndValidation() {
        val stage =
            lone(
                """<field var="n" type="weird"><validate xmlns="http://jabber.org/protocol/xdata-validate" datatype="xs:integer"/></field><field var="l" type="list-single"><validate xmlns="http://jabber.org/protocol/xdata-validate"><open/></validate><option><value>a</value></option></field>"""
            )
        val n = stage.fields.first { it.key == "n" }
        assertEquals("text-single", n.type)
        assertEquals("weird", n.rawType)
        assertEquals("xs:integer", n.datatype)
        val l = stage.fields.first { it.key == "l" }
        assertTrue(l.open)
        assertEquals(listOf(FormOption("a", "a")), l.options)
    }

    @Test
    fun submitFormEchoesEveryField() {
        val stage =
            CommandStage.parse(
                result(cmd(EXECUTING, ADD_USER_FORM.replace("</x>", """<set xmlns="http://jabber.org/protocol/rsm"><after>10</after><max>20</max></set></x>""")))
            )
        val x = submitForm(stage, mapOf("accountjid" to listOf("new@10.0.2.2"), "notes" to listOf("a", "b", "c")))
        assertEquals("submit", x.getAttribute("type"))
        val fields = x.children.filter { it.name == "field" }
        // FORM_TYPE and hidden kept, the fixed one dropped (Cheogram 1d20af98: several values stay several)
        assertEquals(listOf("FORM_TYPE", "accountjid", "password", "notes", "welcome"), fields.map { it.getAttribute("var") })
        fun values(v: String) = fields.first { it.getAttribute("var") == v }.children.map { it.content }
        assertEquals(listOf("http://jabber.org/protocol/admin"), values("FORM_TYPE"))
        assertEquals(listOf("new@10.0.2.2"), values("accountjid"))
        assertEquals(emptyList<String>(), values("password"))
        assertEquals(listOf("a", "b", "c"), values("notes"))
        assertEquals("hidden", fields[0].getAttribute("type"))
        val set = x.findChild("set", "http://jabber.org/protocol/rsm")!!
        assertEquals(listOf("after", "max"), set.ids())
        assertEquals("10", set.findChild("after", "http://jabber.org/protocol/rsm")?.content)
        assertEquals("1000", set.findChild("max", "http://jabber.org/protocol/rsm")?.content)
    }

    @Test
    fun commandIqShape() {
        val iq = commandIq(eu.siacs.conversations.xmpp.Jid.of("10.0.2.2"), "add", null, "execute", null)
        assertEquals(Iq.Type.SET, iq.type)
        val command = iq.findChild("command", Namespace.COMMANDS)!!
        assertEquals("add", command.getAttribute("node"))
        assertEquals("execute", command.getAttribute("action"))
        assertNull(command.getAttribute("sessionid"))
    }
}
