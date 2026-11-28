// Adapted from Cheogram Android (GPL-3.0), Conversation.java CommandSession, commit 494871f:
// rewritten in Kotlin and restructured for Tatrak; not a verbatim copy.
package am.cyberhayq.chat.commands

import androidx.compose.runtime.Immutable
import eu.siacs.conversations.xml.Element
import eu.siacs.conversations.xml.Namespace
import eu.siacs.conversations.xmpp.Jid
import im.conversations.android.xmpp.model.stanza.Iq

internal const val NS_VALIDATE = "http://jabber.org/protocol/xdata-validate"
private const val NS_OOB = "jabber:x:oob"
private const val NS_RSM = "http://jabber.org/protocol/rsm"
private const val NS_STANZAS = "urn:ietf:params:xml:ns:xmpp-stanzas"

/** Cheogram's hidden list-single whose options are custom buttons; pressing one sets it and sends "execute". */
const val ACTIONS_FIELD = "http://jabber.org/protocol/commands#actions"

const val EXECUTING = "executing"
const val COMPLETED = "completed"
const val CANCELED = "canceled"

/** The buttons a stage can show; any other id is a custom one from [ACTIONS_FIELD]. */
object CommandActions {
    const val EXECUTE = "execute"
    const val PREV = "prev"
    const val NEXT = "next"
    const val COMPLETE = "complete"
    const val CANCEL = "cancel"
    const val CLOSE = "close"
}

/** The `<actions>` children shown as buttons; `execute` only names the default, other names are not XEP-0050. */
private val PROTOCOL_ACTIONS = setOf(CommandActions.PREV, CommandActions.NEXT, CommandActions.COMPLETE)

private val FIELD_TYPES =
    setOf(
        "boolean", "fixed", "hidden", "jid-multi", "jid-single", "list-multi", "list-single",
        "text-multi", "text-private", "text-single",
    )

@Immutable data class FormOption(val value: String, val label: String)

@Immutable
data class FormField(
    /** The `var`, or `#<index>` for a field without one (a fixed text). */
    val key: String,
    val variable: String?,
    /** XEP-0004 type; a missing or unknown type is text-single. */
    val type: String,
    /** As sent, echoed in the submit form. */
    val rawType: String?,
    val label: String?,
    val desc: String?,
    val required: Boolean,
    val values: List<String>,
    val options: List<FormOption>,
    /** XEP-0122 `<validate datatype>`. */
    val datatype: String?,
    /** XEP-0122 `<validate><open/>`: a list-single also takes a value of its own. */
    val open: Boolean,
    val range: Boolean,
) {
    val title: String get() = label ?: variable.orEmpty()

    /** Shown and edited: not hidden, not fixed, not the custom-actions field. */
    val fillable: Boolean get() = variable != null && type != "hidden" && type != "fixed" && variable != ACTIONS_FIELD
}

@Immutable data class CommandNote(val text: String, val error: Boolean)

@Immutable data class CommandAction(val id: String, val label: String? = null)

/**
 * An IQ error: its `<text>`, else its condition's name; both null show a generic text. [gone]: XEP-0050's
 * `bad-sessionid` or `session-expired`, the server no longer has the session.
 */
@Immutable data class CommandError(val text: String?, val condition: String?, val timeout: Boolean = false, val gone: Boolean = false)

/** One response of a command session, parsed once; the screen and the next request read only this. */
@Immutable
data class CommandStage(
    val from: Jid?,
    val sessionId: String? = null,
    val status: String? = null,
    val title: String? = null,
    val instructions: List<String> = emptyList(),
    val notes: List<CommandNote> = emptyList(),
    val error: CommandError? = null,
    /** "form" or "result"; null without a form. */
    val formType: String? = null,
    val fields: List<FormField> = emptyList(),
    val reported: List<FormField> = emptyList(),
    /** `<item>`s, each field carrying its `<reported>` label, type and datatype. */
    val items: List<List<FormField>> = emptyList(),
    /** The form's RSM `<set>`, sent back with `<max>1000</max>`. */
    val rsm: Element? = null,
    /** An https link from `jabber:x:oob`: opened in the browser. */
    val link: String? = null,
    /** An `xmpp:` link from `jabber:x:oob`: opened like any xmpp: link. */
    val xmppUri: String? = null,
    /** Buttons in order: cancel first. */
    val actions: List<CommandAction> = emptyList(),
    /** `<actions execute>`: the button drawn as the main one. */
    val defaultAction: String? = null,
    /** The lone list-single or boolean drawn as buttons that execute at once. */
    val grid: FormField? = null,
    /** Completed or canceled with nothing to show: the session closes. */
    val closes: Boolean = false,
) {
    val form: Boolean get() = formType == "form"
    val customActions: Boolean get() = fields.any { it.variable == ACTIONS_FIELD }
    val fillable: List<FormField> get() = if (form) fields.filter { it.fillable } else emptyList()

    companion object {
        fun error(from: Jid?, error: CommandError) =
            CommandStage(from = from, error = error, actions = listOf(CommandAction(CommandActions.CLOSE)))

        /** A response IQ, result or error. */
        fun parse(iq: Element): CommandStage {
            val from = iq.getAttributeAsJid("from")
            if (iq.getAttribute("type") != "result") return error(from, errorOf(iq))
            val command = iq.findChild("command", Namespace.COMMANDS) ?: return error(from, CommandError(null, null))
            val status = command.getAttribute("status")
            val actionsElement = command.findChild("actions", Namespace.COMMANDS)
            // read wherever <actions> sits, before or after the form (Cheogram 18b07510)
            var actions =
                actionsElement
                    ?.children
                    ?.filter { it.namespace == Namespace.COMMANDS && it.name in PROTOCOL_ACTIONS }
                    ?.map { CommandAction(it.name) }
                    .orEmpty()
            // a submit or cancel form is never shown (Cheogram d963e8cd, biboumi)
            val x =
                command.children.firstOrNull {
                    it.name == "x" && it.namespace == Namespace.DATA && it.getAttribute("type").let { t -> t == "form" || t == "result" }
                }
            val formType = x?.getAttribute("type")
            val fields = x?.children.orEmpty().filter { it.isData("field") }.mapIndexed { i, f -> field(f, i) }
            fields.firstOrNull { it.variable == ACTIONS_FIELD }?.let { list -> actions = list.options.map { CommandAction(it.value, it.label) } }
            val reported = x?.findChild("reported", Namespace.DATA)?.children.orEmpty().filter { it.isData("field") }.mapIndexed { i, f -> field(f, i) }
            val items =
                x?.children.orEmpty().filter { it.isData("item") }.map { item ->
                    val cells = item.children.filter { it.isData("field") }
                    reported.map { column ->
                        val cell = cells.firstOrNull { it.getAttribute("var") == column.variable }
                        column.copy(values = cell?.values().orEmpty())
                    }
                }
            val notes =
                command.children
                    .filter { it.name == "note" && it.namespace == Namespace.COMMANDS }
                    .mapNotNull { n -> n.content?.takeIf { it.isNotBlank() }?.let { CommandNote(it.trim(), n.getAttribute("type") == "error") } }
            val oob = command.children.firstOrNull { it.name == "x" && it.namespace == NS_OOB }?.childText("url", NS_OOB)?.trim()
            val scheme = oob?.substringBefore(':', "")?.lowercase()
            // https only (owner, 2026-11-28): a plain http page would open unencrypted
            val link = oob.takeIf { scheme == "https" }
            val xmppUri = oob.takeIf { scheme == "xmpp" }
            val fillable = if (formType == "form") fields.filter { it.fillable } else emptyList()
            val lone = fillable.singleOrNull()?.takeIf { !it.range && !it.open }
            val proceed = actions.count { it.id != CommandActions.CANCEL && it.id != CommandActions.PREV }
            // one hidden "next" still makes a grid (Cheogram f16e3ecb); prev and cancel stay (cde4d04c)
            // not with custom buttons (a tap would lose them) or an execute that goes back (a tap sends execute)
            val grid =
                lone?.takeIf {
                    proceed < 2 && fields.none { f -> f.variable == ACTIONS_FIELD } && actionsElement?.getAttribute("execute") != CommandActions.PREV &&
                        ((it.type == "list-single" && it.options.size in 1..49) || (it.type == "boolean" && it.values.isEmpty()))
                }
            if (grid != null) actions = actions.filter { it.id == CommandActions.CANCEL || it.id == CommandActions.PREV }
            val buttons = actions.toMutableList()
            // executing without actions is a spec violation, but something must move on (Cheogram d0871411)
            if (status == EXECUTING && grid == null && buttons.none { it.id != CommandActions.CANCEL }) buttons += CommandAction(CommandActions.EXECUTE)
            val done = status == COMPLETED || status == CANCELED
            if (buttons.isNotEmpty() || fillable.isNotEmpty()) {
                // prev from a finished stage keeps Close (Cheogram 0dccd5fc)
                if (done) buttons += CommandAction(CommandActions.CLOSE)
                else if (buttons.none { it.id == CommandActions.CANCEL }) buttons.add(0, CommandAction(CommandActions.CANCEL))
            }
            if (buttons.isEmpty()) buttons += CommandAction(CommandActions.CLOSE)
            buttons.sortBy { if (it.id == CommandActions.CANCEL) 0 else 1 }
            val instructions = x?.children.orEmpty().filter { it.isData("instructions") }.mapNotNull { it.content?.trim()?.takeIf(String::isNotEmpty) }
            return CommandStage(
                from = from,
                sessionId = command.getAttribute("sessionid")?.takeIf(String::isNotBlank),
                status = status,
                title = x?.childText("title", Namespace.DATA)?.trim()?.takeIf(String::isNotEmpty),
                instructions = instructions,
                notes = notes,
                formType = formType,
                fields = fields,
                reported = reported,
                items = items,
                rsm = x?.findChild("set", NS_RSM),
                link = link,
                xmppUri = xmppUri,
                actions = buttons,
                defaultAction = actionsElement?.getAttribute("execute"),
                grid = grid,
                // nothing left to show (Cheogram 2717177e); an xmpp: link opens elsewhere
                closes = done && x == null && link == null && notes.isEmpty(),
            )
        }

        private fun Element.childText(name: String, ns: String): String? = findChild(name, ns)?.content

        private fun Element.isData(name: String) = this.name == name && namespace == Namespace.DATA

        private fun Element.values(): List<String> = children.filter { it.isData("value") }.map { it.content.orEmpty() }

        private fun field(f: Element, index: Int): FormField {
            val validate = f.findChild("validate", NS_VALIDATE)
            val raw = f.getAttribute("type")
            val variable = f.getAttribute("var")
            return FormField(
                key = variable ?: "#$index",
                variable = variable,
                type = raw?.takeIf { it in FIELD_TYPES } ?: "text-single",
                rawType = raw,
                label = f.getAttribute("label")?.takeIf(String::isNotBlank),
                desc = f.childText("desc", Namespace.DATA)?.takeIf(String::isNotBlank),
                required = f.hasChild("required", Namespace.DATA),
                values = f.values(),
                options =
                    f.children.filter { it.isData("option") }.mapNotNull { o ->
                        val value = o.childText("value", Namespace.DATA) ?: return@mapNotNull null
                        FormOption(value, o.getAttribute("label")?.takeIf(String::isNotBlank) ?: value)
                    },
                datatype = validate?.getAttribute("datatype"),
                open = validate?.hasChild("open", NS_VALIDATE) == true,
                range = validate?.hasChild("range", NS_VALIDATE) == true,
            )
        }

        private fun errorOf(iq: Element): CommandError {
            val error = iq.findChild("error") ?: return CommandError(null, null)
            val text = error.childText("text", NS_STANZAS)?.trim()?.takeIf(String::isNotEmpty)
            val condition = error.children.firstOrNull { it.name != "text" }?.name
            val gone = error.children.any { it.namespace == Namespace.COMMANDS && (it.name == "bad-sessionid" || it.name == "session-expired") }
            return CommandError(text, condition, gone = gone)
        }
    }
}

/** A boolean field's value as XEP-0004 allows it. */
internal fun isTrue(value: String?) = value == "1" || value == "true"

/**
 * The server's form sent back as `type="submit"`: every field with a var (FORM_TYPE and hidden ones too) with
 * [values] over the received ones, plus the form's RSM `<set>` asking for up to 1000 items (as Cheogram).
 */
fun submitForm(stage: CommandStage, values: Map<String, List<String>>): Element {
    val x = Element("x", Namespace.DATA).setAttribute("type", "submit")
    for (f in stage.fields) {
        val variable = f.variable ?: continue
        if (f.type == "fixed") continue
        val field = x.addChild("field", Namespace.DATA).setAttribute("var", variable)
        f.rawType?.let { field.setAttribute("type", it) }
        for (v in values[f.key] ?: f.values) field.addChild("value", Namespace.DATA).setContent(v)
    }
    stage.rsm?.let { set ->
        val copy = x.addChild("set", NS_RSM)
        for (child in set.children) if (child.name != "max") copy.addChild(child.name, child.namespace).setContent(child.content)
        copy.addChild("max", NS_RSM).setContent("1000")
    }
    return x
}

/** `<iq type="set"><command node action sessionid>[form]</command></iq>`. */
fun commandIq(to: Jid, node: String, sessionId: String?, action: String, form: Element?): Iq {
    val iq = Iq(Iq.Type.SET)
    iq.setTo(to)
    val command = iq.addChild("command", Namespace.COMMANDS)
    command.setAttribute("node", node)
    command.setAttribute("sessionid", sessionId)
    command.setAttribute("action", action)
    form?.let(command::addChild)
    return iq
}
