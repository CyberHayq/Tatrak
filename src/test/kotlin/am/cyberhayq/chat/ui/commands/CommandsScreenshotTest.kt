package am.cyberhayq.chat.ui.commands

import am.cyberhayq.chat.commands.ADD_USER_FORM
import am.cyberhayq.chat.commands.COMPLETED
import am.cyberhayq.chat.commands.CommandError
import am.cyberhayq.chat.commands.CommandStage
import am.cyberhayq.chat.commands.EXECUTING
import am.cyberhayq.chat.commands.FormField
import am.cyberhayq.chat.commands.cmd
import am.cyberhayq.chat.commands.result
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import eu.siacs.conversations.xmpp.Jid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class CommandsScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private object Shown : CommandScreenActions {
        override fun values(field: FormField) = field.values

        override fun set(field: FormField, values: List<String>) {}

        override fun execute(action: String) {}

        override fun pick(value: String) {}

        override fun back() {}

        override fun openLink(url: String) {}

        override fun copy(text: String) {}
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { TatrakTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val commands =
        listOf(
            CommandItem(Jid.of("10.0.2.2"), "ping", "Ping"),
            CommandItem(Jid.of("10.0.2.2"), "add-user", "Add User"),
            CommandItem(Jid.of("10.0.2.2"), "announce", "Send Announcement to Online Users"),
        )

    /** Every field type, a required field left empty, a note and an action set with a default. */
    private val form =
        CommandStage.parse(
            result(
                cmd(
                    EXECUTING,
                    """<note type="warn">The server restarts at midnight</note>""" +
                        ADD_USER_FORM.replace(
                            "</x>",
                            """<field var="role" type="list-single" label="Role"><value>member</value>
<option label="Member"><value>member</value></option><option label="Admin"><value>admin</value></option></field>
<field var="group" type="list-single" label="Group"><option label="Staff"><value>staff</value></option><option label="Guests"><value>guests</value></option></field>
<field var="features" type="list-multi" label="Features"><value>mam</value>
<option label="Archive"><value>mam</value></option><option label="Push"><value>push</value></option></field>
<field var="quota" type="text-single" label="Quota (MB)"><desc>0 means no limit</desc><validate xmlns="http://jabber.org/protocol/xdata-validate" datatype="xs:integer"/></field></x>""",
                        ) + """<actions execute="complete"><prev/><next/><complete/></actions>""",
                ),
            ),
        )

    /** One choice with few options: buttons that proceed at once. */
    private val grid =
        CommandStage.parse(
            result(
                cmd(
                    EXECUTING,
                    """<x xmlns="jabber:x:data" type="form"><title>Restart</title><field var="delay" type="list-single" label="When">
<option label="Now"><value>0</value></option><option label="In 5 minutes"><value>300</value></option>
<option label="In an hour"><value>3600</value></option></field></x>""",
                ),
            ),
        )

    /** A result table as item cards, with an error note and a link. */
    private val items =
        CommandStage.parse(
            result(
                cmd(
                    COMPLETED,
                    """<note type="error">2 sessions could not be listed</note>
<x xmlns="jabber:x:data" type="result"><title>Online users</title>
<reported><field var="jid" type="jid-single" label="User"/><field var="ip" label="Address"/></reported>
<item><field var="jid"><value>tester@10.0.2.2</value></field><field var="ip"><value>10.0.2.15</value></field></item>
<item><field var="jid"><value>friend@10.0.2.2</value></field><field var="ip"><value>10.0.2.16</value></field></item></x>
<x xmlns="jabber:x:oob"><url>https://example.org/report</url></x>""",
                ),
            ),
        )

    @Test
    fun button() = shot("commands_button", dark = false) { Buttons() }

    @Test
    @Config(qualifiers = "+night")
    fun buttonDark() = shot("commands_button_dark", dark = true) { Buttons() }

    @Composable
    private fun Buttons() {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
        ) {
            CommandsButton(expanded = true, onClick = {})
            CommandsButton(expanded = false, onClick = {})
        }
    }

    @Test
    fun sheet() = shot("commands_sheet", dark = false) { CommandsSheetContent(commands) {} }

    @Test
    @Config(qualifiers = "+night")
    fun sheetDark() = shot("commands_sheet_dark", dark = true) { CommandsSheetContent(commands) {} }

    @Test
    fun form() = shot("commands_form", dark = false) { CommandContent("Add User", form, false, false, setOf("accountjid"), Shown) }

    @Test
    @Config(qualifiers = "+night")
    fun formDark() = shot("commands_form_dark", dark = true) { CommandContent("Add User", form, false, false, setOf("accountjid"), Shown) }

    @Test
    fun gridLoading() = shot("commands_grid", dark = false) { CommandContent("Restart", grid, true, true, emptySet(), Shown) }

    @Test
    fun items() = shot("commands_items", dark = false) { CommandContent("Online users", items, false, false, emptySet(), Shown) }

    @Test
    @Config(qualifiers = "+night")
    fun itemsDark() = shot("commands_items_dark", dark = true) { CommandContent("Online users", items, false, false, emptySet(), Shown) }

    @Test
    fun timeout() =
        shot("commands_timeout", dark = false) {
            CommandContent("Ping", CommandStage.error(Jid.of("10.0.2.2"), CommandError(null, null, timeout = true)), false, false, emptySet(), Shown)
        }

    @Test
    fun starting() = shot("commands_starting", dark = false) { CommandContent("Ping", null, true, false, emptySet(), Shown) }
}
