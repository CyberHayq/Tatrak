package eu.siacs.conversations.ui

import am.cyberhayq.chat.ui.search.MessageSearchScreen
import am.cyberhayq.chat.ui.search.SearchActions
import am.cyberhayq.chat.ui.search.SearchMenu
import android.view.View
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import eu.siacs.conversations.R
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.ui.util.SoftKeyboardUtils

/**
 * Tatrak: the Compose message search of [SearchActivity]. The activity keeps the search itself
 * (`XmppConnectionService.search`, the FTS parsing, the saved term) and the result actions; this
 * class draws the results it publishes and calls [SearchActivity.messageAction] back.
 */
internal class SearchCompose(private val activity: SearchActivity, private val initialText: String) :
    SearchActions {

    private val results =
        MessageSearchResults(activity, activity.lifecycleScope, { activity.xmppConnectionService }, activity.isGlobalSearch)

    fun attach(classic: View) {
        activity.composeOver(classic) {
            val current by results.state.collectAsStateWithLifecycle()
            MessageSearchScreen(current, this, initialText = initialText)
        }
    }

    /** Results for [terms], oldest first as the service delivers them; shown newest first. */
    fun publish(terms: List<String>, messages: List<Message>) = results.publish(terms, messages)

    fun clear() = results.clear()

    /** Searching inside one chat: its name goes into the hint. */
    fun setChatName(name: String?) = results.setChatName(name)

    // --- actions ------------------------------------------------------------------------------

    override fun back() {
        SoftKeyboardUtils.hideSoftKeyboard(activity)
        activity.finish()
    }

    override fun search(text: String) = activity.onSearchText(text)

    override fun open(key: String) {
        menu(key, SearchMenu.OPEN)
    }

    override fun menu(key: String, item: SearchMenu) {
        val message = results.message(key) ?: return
        activity.messageAction(
            message,
            when (item) {
                SearchMenu.OPEN -> R.id.open_conversation
                SearchMenu.SHARE -> R.id.share_with
                SearchMenu.COPY -> R.id.copy_message
                SearchMenu.QUOTE -> R.id.quote_message
            },
        )
    }
}
