package am.cyberhayq.chat.ui.settings

import am.cyberhayq.chat.ui.theme.groupedBackground
import am.cyberhayq.chat.ui.theme.TatrakTheme
import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.TwoStatePreference
import androidx.recyclerview.widget.RecyclerView

/**
 * The view of a classic preference page: its own list stays, hidden (`bindPreferences()` attaches
 * the tree and its `android:dependency` links through it), and Compose draws the same tree.
 */
fun preferenceListView(fragment: PreferenceFragmentCompat, classic: View): View {
    classic.visibility = View.GONE
    val compose =
        ComposeView(fragment.requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TatrakTheme {
                    // first composed once the view lifecycle is CREATED, after onViewCreated() set the adapter
                    // shortcut: a screen replaced after the view exists gets a new adapter we do not follow; none of the pages does that
                    fragment.preferenceScreen?.let { PreferenceList(it, fragment.listView.adapter) }
                }
            }
        }
    return FrameLayout(fragment.requireContext()).apply {
        addView(classic)
        addView(compose)
    }
}

/** A row's values copied out of its [Preference], so a change in any of them recomposes the row. */
@Immutable
private data class PreferenceRow(
    val preference: Preference,
    val title: String,
    val summary: String?,
    val icon: Drawable?,
    val checked: Boolean?,
    val enabled: Boolean,
)

@Immutable private class Section(val title: String?, val rows: List<PreferenceRow>)

/**
 * Draws [screen] Telegram-style; [adapter] is the classic list's adapter, which notifies on every change of
 * a title, summary, state or visibility, so it is the one signal to redraw.
 */
@Composable
fun PreferenceList(screen: PreferenceGroup, adapter: RecyclerView.Adapter<*>?, modifier: Modifier = Modifier) {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(adapter) {
        val observer =
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() { version++ }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { version++ }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { version++ }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { version++ }
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { version++ }
            }
        adapter?.registerAdapterDataObserver(observer)
        onDispose { adapter?.unregisterAdapterDataObserver(observer) }
    }
    val sections = remember(screen, version) { sections(screen) }
    // Text without a colour would fall back to black outside a Surface (unreadable in the dark theme)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.groupedBackground).navigationBarsPadding()) {
            sections.forEachIndexed { index, section ->
                if (section.title != null) item { SectionHeader(section.title) }
                // an icon in any row keeps the titles of the section in one column
                val iconSpace = section.rows.any { it.icon != null }
                // stable keys: a row hidden by fragment code must not shift the saved scroll position onto another setting
                itemsIndexed(section.rows, key = { _, row -> row.preference.key ?: row.preference.hashCode() }) { i, row -> PreferenceRowItem(row, iconSpace, divider = i != section.rows.lastIndex) }
                if (index != sections.lastIndex) item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}

/** Visible preferences only; loose top-level rows share one untitled section, a category without visible rows is left out. */
private fun sections(screen: PreferenceGroup): List<Section> {
    val sections = mutableListOf<Section>()
    var loose: MutableList<PreferenceRow>? = null
    for (preference in screen.visibleChildren()) {
        if (preference is PreferenceCategory) {
            loose = null
            val rows = preference.visibleChildren().map(::row)
            if (rows.isNotEmpty()) sections += Section(preference.title?.toString(), rows)
        } else {
            if (loose == null) loose = mutableListOf<PreferenceRow>().also { sections += Section(null, it) }
            loose.add(row(preference))
        }
    }
    return sections
}

private fun PreferenceGroup.visibleChildren() = (0 until preferenceCount).map(::getPreference).filter { it.isVisible }

private fun row(preference: Preference) =
    PreferenceRow(
        preference,
        preference.title?.toString().orEmpty(),
        preference.summary?.toString()?.takeIf { it.isNotEmpty() },
        preference.icon,
        (preference as? TwoStatePreference)?.isChecked,
        preference.isEnabled,
    )

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().semantics { heading() }.background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

// performClick() is the call the classic row makes: change listeners, dialogs, intents and app:fragment run as before
@SuppressLint("RestrictedApi")
@Composable
private fun PreferenceRowItem(row: PreferenceRow, iconSpace: Boolean, divider: Boolean) {
    val click = { row.preference.performClick() }
    val preference = row.preference
    // a key-less plain Preference with nothing to open (UnifiedPush's explanation) is text, not a button;
    // a keyed one may be handled only in the fragment's onPreferenceTreeClick (remove_trusted_certificates, call_ringtone)
    val inert = preference.javaClass == Preference::class.java && preference.key == null && preference.onPreferenceClickListener == null && preference.intent == null && preference.fragment == null
    val action =
        if (inert) Modifier
        else if (row.checked != null) Modifier.toggleable(row.checked, enabled = row.enabled, role = Role.Switch) { click() }
        else Modifier.clickable(enabled = row.enabled, role = Role.Button, onClick = click)
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth().then(action).alpha(if (row.enabled) 1f else 0.4f).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (row.icon != null) {
                val px = with(LocalDensity.current) { 24.dp.roundToPx() }
                val bitmap = remember(row.icon, px) { row.icon.toBitmap(px, px).asImageBitmap() }
                Icon(bitmap, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            } else if (iconSpace) {
                Spacer(Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                // UnifiedPush's explanation is a summary without a title
                if (row.title.isNotEmpty()) Text(row.title, fontSize = 16.sp)
                if (row.summary != null) Text(row.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.checked != null) Switch(checked = row.checked, onCheckedChange = null)
        }
        if (divider) {
            HorizontalDivider(Modifier.padding(start = if (iconSpace) 56.dp else 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
