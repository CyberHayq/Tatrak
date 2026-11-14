package am.cyberhayq.chat.ui.topics

import am.cyberhayq.chat.ui.chatlist.UnreadBadge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.siacs.conversations.R

/** One topic of a group. Not wired to data yet (see knowledge/project/group-topics.md); the owner asked for the component. */
data class Topic(
    val id: String,
    val name: String,
    val lastSender: String,
    val lastText: String,
    val time: String,
    val unread: Int,
    val pinned: Boolean,
)

/** The topics of a group; the caller sorts them (pinned first). */
@Composable
fun TopicsList(topics: List<Topic>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier) {
        items(topics, key = { it.id }) { topic -> TopicRow(topic, onOpen) }
    }
}

@Composable
private fun TopicRow(topic: Topic, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val unread = if (topic.unread > 0) stringResource(R.string.tatrak_topic_unread, topic.unread) else null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable { onOpen(topic.id) }
                // one node: name, preview, time and the unread count are read together
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).background(colors.primary.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
        ) {
            Icon(painterResource(R.drawable.tatrak_ic_tag_24dp), null, tint = colors.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    topic.name,
                    color = colors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (topic.pinned) {
                    Icon(
                        painterResource(R.drawable.tatrak_ic_pin_24dp),
                        null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp).size(14.dp),
                    )
                }
            }
            Text(
                "${topic.lastSender}: ${topic.lastText}",
                color = colors.onSurfaceVariant,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(topic.time, color = colors.onSurfaceVariant, fontSize = 13.sp)
            if (unread != null) UnreadBadge(topic.unread, colors.primary, Modifier.clearAndSetSemantics { contentDescription = unread })
        }
    }
}
