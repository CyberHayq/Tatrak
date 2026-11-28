package am.cyberhayq.chat.commands

import eu.siacs.conversations.xmpp.Jid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
