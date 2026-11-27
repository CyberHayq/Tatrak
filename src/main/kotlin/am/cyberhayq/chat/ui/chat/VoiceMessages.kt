package am.cyberhayq.chat.ui.chat

import eu.siacs.conversations.entities.Message
import eu.siacs.conversations.entities.Transferable
import eu.siacs.conversations.utils.MessageUtils

/**
 * A downloaded audio file that plays in its bubble: the rule of ChatSnapshot's [MessageKind.AUDIO]
 * branch, shared with [VoiceSession]. XMPP has no voice type, so a shared music file counts too.
 */
fun isVoice(message: Message): Boolean {
    if (!message.isFileOrImage || waitsForFile(message)) return false
    when (message.encryption) {
        Message.ENCRYPTION_PGP,
        Message.ENCRYPTION_DECRYPTION_FAILED,
        Message.ENCRYPTION_IMPORTED_PQ_UNAVAILABLE,
        Message.ENCRYPTION_IMPORTED_PQ_FAILED -> return false
    }
    val params = message.fileParams
    return !(params.width > 0 && params.height > 0) && params.runtime > 0
}

/**
 * An audio file not downloaded yet, still downloading or deleted. It has no play time yet, so its type
 * comes from the mime type guessed from the URL (`MimeUtils` maps `.ogg` to `application/ogg`).
 */
fun isVoiceNotReady(message: Message): Boolean {
    if (isVoice(message) || !(message.treatAsDownloadable() || waitsForFile(message))) return false
    val mime = message.mimeType ?: return false
    return mime.startsWith("audio/") || mime == "application/ogg"
}

// the conditions under which ChatSnapshot shows a file bubble instead of the content
private fun waitsForFile(message: Message): Boolean {
    val transferable = message.transferable
    return message.isDeleted ||
        MessageUtils.unInitiatedButKnownSize(message) ||
        (transferable != null && transferable.status != Transferable.STATUS_UPLOADING)
}
