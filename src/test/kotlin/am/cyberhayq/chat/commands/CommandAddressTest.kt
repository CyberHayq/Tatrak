package am.cyberhayq.chat.commands

import am.cyberhayq.chat.ui.commands.commandItem
import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandAddressTest {
    private val bare = Jid.of("bot@10.0.2.2")
    private val phone = Jid.of("bot@10.0.2.2/phone")
    private val pc = Jid.of("bot@10.0.2.2/pc")

    @Test
    fun firstResourceWithCommands() {
        assertEquals(pc, commandAddress(bare, listOf(phone to false, pc to true), bareHasCommands = true, roomHasCommands = false))
    }

    @Test
    fun bareJidWhenItsOwnDiscoHasThem() {
        // also when the contact is offline (Cheogram 9b48d181)
        assertEquals(bare, commandAddress(bare, emptyList(), bareHasCommands = true, roomHasCommands = false))
    }

    @Test
    fun roomWithTheFeature() {
        val room = Jid.of("room@conference.10.0.2.2")
        assertEquals(room, commandAddress(room, emptyList(), bareHasCommands = false, roomHasCommands = true))
        assertNull(commandAddress(room, emptyList(), bareHasCommands = false, roomHasCommands = false))
    }

    @Test
    fun domainJid() {
        val gateway = Jid.of("gateway.10.0.2.2")
        assertEquals(gateway, commandAddress(gateway, emptyList(), bareHasCommands = false, roomHasCommands = false))
    }

    @Test
    fun none() {
        assertNull(commandAddress(bare, listOf(phone to false), bareHasCommands = false, roomHasCommands = false))
    }

    @Test
    fun commandItemMarksOtherAddressAndBlocksOtherDomain() {
        // no item jid, or a resource of the chat's address: nothing shown, runs
        commandItem(bare, null, "n", "N").let { assertEquals(bare, it.jid); assertNull(it.via); assertFalse(it.blocked) }
        commandItem(bare, pc, "n", "N").let { assertNull(it.via); assertFalse(it.blocked) }
        // the same domain at another address: shown with the address, runs
        commandItem(bare, Jid.of("10.0.2.2"), "n", "N").let { assertEquals("10.0.2.2", it.via); assertFalse(it.blocked) }
        // another domain: listed, never started (owner, 2026-11-28)
        commandItem(bare, Jid.of("pay@evil.example/x"), "n", "N").let { assertEquals("pay@evil.example", it.via); assertTrue(it.blocked) }
        // the same IDN domain in Unicode and in Punycode
        commandItem(Jid.of("bot@bücher.example"), Jid.of("bot@xn--bcher-kva.example/c"), "n", "N").let { assertNull(it.via); assertFalse(it.blocked) }
    }
}
