package am.cyberhayq.chat.ui.chatlist

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Telegram-style timestamps: time for today, weekday for this week, date otherwise. */
class ChatTimeFormatter(is24Hour: Boolean, locale: Locale) {
    private val time = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)
    private val weekday = DateTimeFormatter.ofPattern("EEE", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val fullDate = DateTimeFormatter.ofPattern("dd.MM.yy", locale)

    fun format(timestamp: Long, today: LocalDate = LocalDate.now()): String {
        val dateTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        val date = dateTime.toLocalDate()
        return when {
            !date.isBefore(today) -> time.format(dateTime)
            date.isAfter(today.minusDays(7)) -> weekday.format(dateTime)
            date.year == today.year -> dayMonth.format(dateTime)
            else -> fullDate.format(dateTime)
        }
    }
}

@Composable
fun rememberChatTimeFormatter(): ChatTimeFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { ChatTimeFormatter(DateFormat.is24HourFormat(context), locale) }
}
