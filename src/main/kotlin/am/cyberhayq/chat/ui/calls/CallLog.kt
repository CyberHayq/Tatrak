package am.cyberhayq.chat.ui.calls

import java.time.Instant
import java.time.ZoneId

/** One call as stored: a `TYPE_RTP_SESSION` message, newest first in a list. */
data class CallRecord(
    /** Message uuid. */
    val key: String,
    /** Conversation uuid. */
    val chat: String,
    val time: Long,
    val received: Boolean,
    /** `RtpSessionStatus.successful`: the call was answered. */
    val successful: Boolean,
    /** `RtpSessionStatus.duration`, in milliseconds; 0 when unknown or not answered. */
    val duration: Long,
) {
    /** Like the chat bubble: received, not answered, no duration. */
    val missed: Boolean
        get() = received && !successful && duration <= 0
}

/** A row of the call list: one call or several alike calls in a row (shown as "Name (3)"). */
data class CallGroup(val newest: CallRecord, val count: Int)

object CallLog {

    /**
     * Groups calls the way Telegram does: neighbours in the newest-first [records] with the same
     * chat, direction, missed state and day become one row.
     */
    fun group(records: List<CallRecord>, zone: ZoneId = ZoneId.systemDefault()): List<CallGroup> {
        val groups = ArrayList<CallGroup>()
        for (record in records) {
            val last = groups.lastOrNull()
            if (last != null && alike(last.newest, record, zone)) {
                groups[groups.lastIndex] = last.copy(count = last.count + 1)
            } else {
                groups += CallGroup(record, 1)
            }
        }
        return groups
    }

    private fun alike(a: CallRecord, b: CallRecord, zone: ZoneId) =
        a.chat == b.chat && a.received == b.received && a.missed == b.missed && day(a.time, zone) == day(b.time, zone)

    private fun day(time: Long, zone: ZoneId) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
}
