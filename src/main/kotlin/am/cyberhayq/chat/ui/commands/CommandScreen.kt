package am.cyberhayq.chat.ui.commands

import am.cyberhayq.chat.commands.CommandActions
import am.cyberhayq.chat.commands.CommandError
import am.cyberhayq.chat.commands.CommandSession
import am.cyberhayq.chat.commands.CommandStage
import am.cyberhayq.chat.commands.EXECUTING
import am.cyberhayq.chat.commands.FormField
import am.cyberhayq.chat.commands.isTrue
import am.cyberhayq.chat.ui.chat.BlockSwipeBack
import am.cyberhayq.chat.ui.common.TatrakTopBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import eu.siacs.conversations.R

/** What the form screen calls out of the session; a plain interface so screenshots need no session. */
interface CommandScreenActions {
    fun values(field: FormField): List<String>

    fun set(field: FormField, values: List<String>)

    fun execute(action: String)

    fun pick(value: String)

    fun back()

    fun openLink(url: String)

    fun copy(text: String)
}

/** The running command, full screen over the chat; Back cancels it, asking first when a field was edited. */
@Composable
fun CommandScreen(session: CommandSession, openLink: (String) -> Unit, copy: (String) -> Unit) {
    var ask by rememberSaveable { mutableStateOf(false) }
    val actions =
        remember(session) {
            object : CommandScreenActions {
                override fun values(field: FormField) = session.values(field)

                override fun set(field: FormField, values: List<String>) = session.set(field, values)

                override fun execute(action: String) = session.execute(action)

                override fun pick(value: String) = session.pick(value)

                override fun back() {
                    if (session.edited && session.stage?.status == EXECUTING) ask = true else session.cancel()
                }

                override fun openLink(url: String) = openLink(url)

                override fun copy(text: String) = copy(text)
            }
        }
    BlockSwipeBack(true)
    BackHandler { actions.back() }
    CommandContent(session.name, session.stage, session.loading, session.slow, session.missing, actions, session.generation)
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(stringResource(R.string.tatrak_command_cancel_title)) },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    session.cancel()
                }) { Text(stringResource(R.string.tatrak_command_cancel_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text(stringResource(R.string.tatrak_command_keep)) } },
        )
    }
}

@Composable
internal fun CommandContent(
    name: String,
    stage: CommandStage?,
    loading: Boolean,
    slow: Boolean,
    missing: Set<String>,
    actions: CommandScreenActions,
    /** Changes with every new stage, not with an error kept over the same form. */
    generation: Int = 0,
) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        Column(Modifier.fillMaxSize().background(colors.surface)) {
            TatrakTopBar(title = stage?.title ?: name, onBack = actions::back)
            // landscape: keep the fields clear of a side navigation bar or cutout, like the top bar
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                if (stage == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        stageItems(stage, generation, loading, missing, actions)
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                if (slow) {
                    Text(
                        stringResource(R.string.tatrak_command_slow),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    )
                }
            }
            if (stage != null) ActionBar(stage, loading, actions)
        }
    }
}

private fun LazyListScope.stageItems(stage: CommandStage, generation: Int, loading: Boolean, missing: Set<String>, actions: CommandScreenActions) {
    stage.error?.let { error -> item("error") { Note(errorText(error), error = true) } }
    itemsIndexed(stage.instructions, key = { i, _ -> "i$i" }) { _, text -> Text(text, style = MaterialTheme.typography.bodyLarge) }
    itemsIndexed(stage.notes, key = { i, _ -> "n$i" }) { _, note -> Note(note.text, note.error) }
    stage.link?.let { link ->
        item("link") {
            Column {
                Text(link, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { actions.openLink(link) }) { Text(stringResource(R.string.tatrak_command_open_link)) }
            }
        }
    }
    val grid = stage.grid
    // keys unique by position: a form may repeat a var; edits of one stage keep their composables (Cheogram 2cc5d3b2);
    // a new stage gets new rows, so a shown password is not shown on the next stage
    itemsIndexed(stage.fields.filter { it.type != "hidden" && it.key != grid?.key }, key = { i, f -> "f$generation:$i:${f.key}" }) { _, field ->
        if (stage.form && field.fillable) Field(field, field.key in missing, actions) else ReadOnlyField(field, actions)
    }
    if (grid != null) item("grid") { Grid(grid, !loading, actions) }
    itemsIndexed(stage.items, key = { i, _ -> "r$i" }) { _, cells -> ItemCard(cells, actions) }
}

@Composable
private fun errorText(error: CommandError): String =
    when {
        error.timeout -> stringResource(R.string.tatrak_command_timeout)
        else -> error.text ?: error.condition ?: stringResource(R.string.tatrak_command_failed)
    }

@Composable
private fun Note(text: String, error: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (error) colors.errorContainer else colors.surfaceVariant,
        contentColor = if (error) colors.onErrorContainer else colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            if (error) {
                Icon(painterResource(R.drawable.ic_error_24dp), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private val INTEGERS = setOf("xs:int", "xs:long", "xs:short", "xs:byte", "xs:unsignedInt", "xs:unsignedLong", "xs:unsignedShort", "xs:unsignedByte")

/** XEP-0122 datatype → keyboard, as Cheogram's setupInputType; Compose has no date-time keyboard, so dates get text. */
internal fun keyboardFor(field: FormField): KeyboardOptions {
    val type =
        when {
            field.type == "text-private" -> KeyboardType.Password
            field.datatype?.endsWith("nteger") == true || field.datatype in INTEGERS -> KeyboardType.Number
            field.datatype == "xs:decimal" || field.datatype == "xs:double" -> KeyboardType.Decimal
            field.datatype == "xs:anyURI" -> KeyboardType.Uri
            field.datatype == "html:tel" -> KeyboardType.Phone
            field.datatype == "html:email" || field.type.startsWith("jid-") -> KeyboardType.Email
            else -> KeyboardType.Text
        }
    val sentences = type == KeyboardType.Text && field.datatype == null && field.type != "text-private"
    return KeyboardOptions(capitalization = if (sentences) KeyboardCapitalization.Sentences else KeyboardCapitalization.None, keyboardType = type, autoCorrectEnabled = sentences)
}

@Composable
private fun FieldTitle(field: FormField) {
    if (field.title.isNotEmpty()) Text(field.title + if (field.required) " *" else "", style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Supporting(field: FormField, missing: Boolean) {
    when {
        missing -> Text(stringResource(R.string.tatrak_command_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        field.desc != null -> Text(field.desc, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Field(field: FormField, missing: Boolean, actions: CommandScreenActions) {
    val values = actions.values(field)
    when (field.type) {
        "boolean" -> {
            val checked = isTrue(values.firstOrNull())
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) { actions.set(field, listOf(it.toString())) },
                ) {
                    Checkbox(checked, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(field.title, style = MaterialTheme.typography.bodyLarge)
                }
                Supporting(field, missing)
            }
        }
        "list-multi" ->
            Column {
                FieldTitle(field)
                for (option in field.options) {
                    val checked = option.value in values
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) {
                                actions.set(field, multiValues(field, values, option.value, it))
                            }.padding(vertical = 6.dp),
                    ) {
                        Checkbox(checked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Supporting(field, missing)
            }
        "list-single" -> {
            val value = values.firstOrNull()
            if (value.isNullOrEmpty() || field.open) {
                // a radio list; <open/> also takes a value of its own
                Column {
                    FieldTitle(field)
                    for (option in field.options) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth().selectable(option.value == value, role = Role.RadioButton) {
                                    actions.set(field, listOf(option.value))
                                }.padding(vertical = 6.dp),
                        ) {
                            RadioButton(option.value == value, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (field.open) {
                        OutlinedTextField(
                            value = value.orEmpty().takeIf { v -> field.options.none { it.value == v } }.orEmpty(),
                            onValueChange = { actions.set(field, listOf(it)) },
                            label = { Text(stringResource(R.string.tatrak_command_other)) },
                            singleLine = true,
                            keyboardOptions = keyboardFor(field),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Supporting(field, missing)
                }
            } else {
                var open by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                    OutlinedTextField(
                        value = field.options.firstOrNull { it.value == value }?.label ?: value,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(field.title + if (field.required) " *" else "") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                        supportingText = if (missing || field.desc != null) ({ Supporting(field, missing) }) else null,
                        isError = missing,
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (option in field.options) {
                            DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                open = false
                                actions.set(field, listOf(option.value))
                            })
                        }
                    }
                }
            }
        }
        else -> {
            val multi = field.type == "text-multi" || field.type == "jid-multi"
            val secret = field.type == "text-private"
            var shown by remember { mutableStateOf(false) }
            OutlinedTextField(
                // several values, one per line (Cheogram 1d20af98)
                value = if (multi) values.joinToString("\n") else values.firstOrNull().orEmpty(),
                onValueChange = { actions.set(field, if (multi) it.split("\n") else listOf(it)) },
                label = if (field.title.isNotEmpty()) ({ Text(field.title + if (field.required) " *" else "") }) else null,
                supportingText = if (missing || field.desc != null) ({ Supporting(field, missing) }) else null,
                isError = missing,
                singleLine = !multi,
                minLines = if (multi) 3 else 1,
                keyboardOptions = keyboardFor(field),
                visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon =
                    if (secret) {
                        {
                            IconButton(onClick = { shown = !shown }) {
                                Icon(
                                    painterResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility_24dp),
                                    contentDescription = stringResource(if (shown) R.string.tatrak_command_hide_password else R.string.tatrak_command_show_password),
                                )
                            }
                        }
                    } else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A fixed text, or a field of a result form: label and value, values as links where they are addresses. */
@Composable
private fun ReadOnlyField(field: FormField, actions: CommandScreenActions) {
    if (field.type == "fixed") {
        Column {
            field.label?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(field.values.joinToString("\n"), style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        ValueRow(field, actions)
    }
}

/** `jid-single`, `xs:anyURI` and `html:tel` values open as links; a long press copies any value. */
/** XEP-0004 3.3: list-multi values go back in the offered order, not in tap order; values not offered stay last. */
internal fun multiValues(field: FormField, values: List<String>, value: String, checked: Boolean): List<String> =
    (if (checked) values + value else values - value).sortedBy { v -> field.options.indexOfFirst { it.value == v }.takeIf { it >= 0 } ?: Int.MAX_VALUE }

internal fun linkOf(field: FormField, value: String): String? =
    when {
        value.isBlank() -> null
        field.type == "jid-single" || field.type == "jid-multi" -> "xmpp:$value"
        field.datatype == "xs:anyURI" -> value
        field.datatype == "html:tel" -> "tel:$value"
        else -> null
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ValueRow(field: FormField, actions: CommandScreenActions) {
    Column(Modifier.fillMaxWidth()) {
        if (field.title.isNotEmpty()) Text(field.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (value in field.values) {
            val link = linkOf(field, value)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (link != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                textDecoration = if (link != null) TextDecoration.Underline else null,
                modifier =
                    Modifier.fillMaxWidth()
                        .combinedClickable(enabled = true, onClick = { link?.let(actions::openLink) }, onLongClick = { actions.copy(value) })
                        .padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ItemCard(cells: List<FormField>, actions: CommandScreenActions) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (cell in cells) if (cell.values.any { it.isNotEmpty() } && cell.type != "hidden") ValueRow(cell, actions)
        }
    }
}

/** The lone choice as buttons that proceed at once; the current value is the filled one. */
@Composable
private fun Grid(field: FormField, enabled: Boolean, actions: CommandScreenActions) {
    val options =
        if (field.type == "boolean") listOf("false" to stringResource(R.string.no), "true" to stringResource(R.string.yes))
        else field.options.map { it.value to it.label }
    val current = field.values.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldTitle(field)
        field.desc?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        for ((value, label) in options) {
            if (value == current) Button(onClick = { actions.pick(value) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
            else OutlinedButton(onClick = { actions.pick(value) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }
}

@Composable
private fun actionLabel(stage: CommandStage, id: String): String =
    when (id) {
        CommandActions.PREV -> stringResource(R.string.tatrak_command_prev)
        CommandActions.NEXT -> stringResource(R.string.tatrak_command_next)
        CommandActions.COMPLETE -> stringResource(R.string.tatrak_command_complete)
        CommandActions.EXECUTE -> stringResource(R.string.tatrak_command_execute)
        CommandActions.CANCEL -> stringResource(R.string.cancel)
        CommandActions.CLOSE -> stringResource(R.string.tatrak_command_close)
        else -> stage.actions.firstOrNull { it.id == id }?.label ?: id
    }

/** Cancel and Back on the left as text buttons; the main action (`<actions execute>`, else the last) filled. */
@Composable
private fun ActionBar(stage: CommandStage, loading: Boolean, actions: CommandScreenActions) {
    val ids = stage.actions.map { it.id }
    val main = stage.defaultAction?.takeIf { it in ids && !stage.customActions } ?: ids.lastOrNull { it != CommandActions.CANCEL && it != CommandActions.PREV }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            for (id in ids.filter { it == CommandActions.CANCEL || it == CommandActions.PREV }) {
                // Cancel always works; the others wait for the answer
                TextButton(onClick = { actions.execute(id) }, enabled = id == CommandActions.CANCEL || !loading) { Text(actionLabel(stage, id)) }
            }
            Spacer(Modifier.weight(1f))
            for (id in ids.filter { it != CommandActions.CANCEL && it != CommandActions.PREV }) {
                if (id == main) Button(onClick = { actions.execute(id) }, enabled = !loading) { Text(actionLabel(stage, id)) }
                else TextButton(onClick = { actions.execute(id) }, enabled = !loading) { Text(actionLabel(stage, id)) }
            }
        }
    }
}
