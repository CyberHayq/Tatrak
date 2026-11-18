package am.cyberhayq.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSelectionTest {

    // list indices 0..5, newest first; 2 is a date chip
    private val rows = listOf("a", "b", null, "c", "d", "e")
    private val keyAt: (Int) -> String? = { rows.getOrNull(it) }

    @Test
    fun dragSelectsTheRangeAndSkipsOtherItems() =
        assertEquals(listOf("b", "c", "d"), dragSelection(listOf("b"), 1, 4, true, keyAt).sorted())

    @Test
    fun dragBackUnselectsWhatItAddedButNotWhatWasSelectedBefore() {
        val base = listOf("e", "b")
        assertEquals(setOf("e", "b", "c", "d"), dragSelection(base, 1, 4, true, keyAt).toSet())
        assertEquals(setOf("e", "b", "a"), dragSelection(base, 1, 0, true, keyAt).toSet())
    }

    @Test
    fun dragFromAnUnselectedRowUnselects() =
        assertEquals(listOf("a"), dragSelection(listOf("a", "c", "d"), 4, 3, false, keyAt))

    @Test
    fun dragStopsAtTheCapFromTheStartOutwards() {
        val many = (0 until 150).map { "m$it" }
        val result = dragSelection(listOf("m10"), 10, 149, true) { many.getOrNull(it) }
        assertEquals(MAX_SELECTED, result.size)
        assertEquals("m109", result.last())
    }

    @Test
    fun toggleRespectsTheCap() {
        val full = (0 until MAX_SELECTED).map { "m$it" }
        assertEquals(full, toggled(full, "x"))
        assertEquals(full - "m3", toggled(full, "m3"))
        assertEquals(listOf("x"), toggled(emptyList(), "x"))
    }

    @Test
    fun copyNamesEachRunOfOneSender() =
        assertEquals(
            "Ani:\nHi\n\nHow are you?\n\nMe:\nFine",
            selectionCopyText(
                listOf(SelectedText("ani@x", "Ani", "Hi"), SelectedText("ani@x", "Ani", "How are you?"), SelectedText(null, "Me", "Fine")),
                withNames = true,
            ),
        )

    /** Two people with the same display name are two runs: the sender's identity decides, not the name. */
    @Test
    fun copySeparatesSendersThatShareAName() =
        assertEquals(
            "Ani:\nHi\n\nAni:\nHello",
            selectionCopyText(listOf(SelectedText("room@x/ani", "Ani", "Hi"), SelectedText("room@x/ani2", "Ani", "Hello")), withNames = true),
        )

    @Test
    fun copyOfOneMessageOrInSavedMessagesHasNoNames() {
        assertEquals("Hi", selectionCopyText(listOf(SelectedText("ani@x", "Ani", "Hi")), withNames = true))
        assertEquals("Hi\n\nFine", selectionCopyText(listOf(SelectedText(null, "Me", "Hi"), SelectedText(null, "Me", "Fine")), withNames = false))
    }
}
