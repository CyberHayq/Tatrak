package am.cyberhayq.chat.ui.chat

import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.entities.Conversational
import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.Transferable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which messages play in their bubble (issue #7, entry 2). */
class VoiceMessagesTest {
    private val chat =
        Conversation(
            "c1", "Alice", null, "a1", null, 0, Conversation.STATUS_AVAILABLE,
            Conversational.MODE_SINGLE, Conversation.Attributes.parse(null),
        )

    private fun file(body: String = "https://up.example.com/v.m4a|4000|0|0|19000", encryption: Int = Message.ENCRYPTION_NONE, status: Int = Message.STATUS_RECEIVED) =
        Message(chat, body, encryption, status).apply {
            type = Message.TYPE_FILE
            relativeFilePath = Message.StorageLocation(java.io.File("attachments/v.m4a"), false)
        }

    private class Transfer(private val status: Int) : Transferable {
        override fun start() = false
        override fun getStatus() = status
        override fun getFileSize(): Long = 4000
        override fun getProgress() = 0
        override fun cancel() = Unit
    }

    @Test fun aDownloadedVoiceFileIsVoice() {
        val voice = file()
        assertTrue(isVoice(voice))
        assertFalse(isVoiceNotReady(voice))
    }

    @Test fun anUndownloadedUploadIsNotReady() {
        // HTTP upload before the download: a text message whose body is the URL
        val offer = Message(chat, "https://up.example.com/v.m4a", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply { setOob(true) }
        assertFalse(isVoice(offer))
        assertTrue(isVoiceNotReady(offer))
    }

    @Test fun anUndownloadedOggIsNotReady() {
        val offer = Message(chat, "https://up.example.com/v.ogg", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply { setOob(true) }
        assertTrue(isVoiceNotReady(offer))
    }

    @Test fun aDeletedFileIsNotReady() {
        val deleted = file().apply { isDeleted = true }
        assertFalse(isVoice(deleted))
        assertTrue(isVoiceNotReady(deleted))
    }

    @Test fun anOwnFileStillUploadingIsVoice() {
        val sending = file(status = Message.STATUS_UNSEND).apply { transferable = Transfer(Transferable.STATUS_UPLOADING) }
        assertTrue(isVoice(sending))
        assertFalse(isVoiceNotReady(sending))
    }

    @Test fun aDownloadingFileIsNotReady() {
        // a download in progress: still the text message with the URL
        val loading =
            Message(chat, "https://up.example.com/v.m4a", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED).apply {
                setOob(true)
                transferable = Transfer(Transferable.STATUS_DOWNLOADING)
            }
        assertFalse(isVoice(loading))
        assertTrue(isVoiceNotReady(loading))
    }

    @Test fun aPictureIsNeither() {
        val picture = file("https://up.example.com/p.jpg|4000|640|480").apply { type = Message.TYPE_IMAGE }
        assertFalse(isVoice(picture))
        assertFalse(isVoiceNotReady(picture))
    }

    @Test fun encryptedFilesThatCannotPlayAreNotVoice() {
        for (encryption in listOf(
            Message.ENCRYPTION_PGP,
            Message.ENCRYPTION_DECRYPTION_FAILED,
            Message.ENCRYPTION_IMPORTED_PQ_UNAVAILABLE,
            Message.ENCRYPTION_IMPORTED_PQ_FAILED,
        )) {
            assertFalse("encryption $encryption", isVoice(file(encryption = encryption)))
        }
    }
}
