package eu.siacs.conversations.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.siacs.conversations.entities.Account
import eu.siacs.conversations.entities.Conversation
import eu.siacs.conversations.persistance.DatabaseBackend
import eu.siacs.conversations.ui.ReviewFlowsTest
import eu.siacs.conversations.xmpp.Jid
import eu.siacs.conversations.xmpp.XmppConnection
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/** "Delete account" erases the files of the account's closed chats too, which the service has not loaded. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(
    application = ReviewFlowsTest.TestApp::class,
    sdk = [35],
    shadows = [DeleteAccountFilesTest.StageBackend::class, ReviewFlowsTest.NoAxolotlStore::class, ReviewFlowsTest.NoPushRenewal::class],
)
class DeleteAccountFilesTest {

    /** The database calls of the account removal, without SQLCipher (it does not run on the JVM). */
    @Implements(value = DatabaseBackend::class, isInAndroidSdk = false)
    class StageBackend {
        @Implementation
        fun getConversations(status: Int) = CopyOnWriteArrayList(stored.filter { it.status == status })

        @Implementation
        fun deleteMessagesInConversation(conversation: Conversation): List<DatabaseBackend.FilePathInfo> {
            emptied += conversation.uuid
            return files[conversation.uuid].orEmpty()
        }

        @Implementation fun deleteAccount(account: Account) = true

        @Implementation fun vacuum() = Unit

        companion object {
            val stored = ArrayList<Conversation>()
            val files = HashMap<String, List<DatabaseBackend.FilePathInfo>>()
            val emptied = ArrayList<String>()
        }
    }

    private class TestService(context: Context) : XmppConnectionService() {
        init {
            attachBaseContext(context)
        }

        override fun toggleForegroundService() = Unit

        override fun updateAccountUi() = Unit
    }

    private fun fileInfo(uuid: String, file: File): DatabaseBackend.FilePathInfo =
        DatabaseBackend.FilePathInfo::class.java
            .getDeclaredConstructor(String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(uuid, file.absolutePath, false)

    private fun stored(uuid: String, account: Account, status: Int) =
        Conversation(uuid, uuid, null, account.uuid, Jid.of("$uuid@example.com"), 0, status, Conversation.MODE_SINGLE, Conversation.Attributes.parse(null))

    @Test
    fun closedChatsLoseTheirFilesToo() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val service = TestService(app)
        service.databaseBackend = DatabaseBackend.isolated(app, "unused", ByteArray(0))
        val account = Account(Jid.of("tester@example.com"), "fixture")
        val other = Account(Jid.of("other@example.com"), "fixture")
        account.xmppConnection = XmppConnection(account, service)
        ReflectionHelpers.setField(service, "accounts", CopyOnWriteArrayList(listOf(account, other)))
        val open = Conversation("open@example.com", account, Jid.of("open@example.com"), Conversation.MODE_SINGLE)
        ReflectionHelpers.getField<MutableList<Conversation>>(service, "conversations").add(open)
        val closed = stored("closed", account, Conversation.STATUS_ARCHIVED)
        val otherClosed = stored("other-closed", other, Conversation.STATUS_ARCHIVED)
        StageBackend.stored += listOf(stored(open.uuid, account, Conversation.STATUS_AVAILABLE), closed, otherClosed)
        val openFile = File(app.filesDir, "open.png").apply { writeText("x") }
        val closedFile = File(app.filesDir, "closed.png").apply { writeText("x") }
        StageBackend.files[open.uuid] = listOf(fileInfo(java.util.UUID.randomUUID().toString(), openFile))
        StageBackend.files[closed.uuid] = listOf(fileInfo(java.util.UUID.randomUUID().toString(), closedFile))
        try {
            service.deleteAccount(account)
            // the writer task hands the files to FILE_ATTACHMENT_EXECUTOR
            val deadline = System.nanoTime() + 5_000_000_000
            while ((openFile.exists() || closedFile.exists()) && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(openFile.exists())
            assertFalse(closedFile.exists())
            // the other account's closed chat keeps its messages
            assertEquals(listOf(open.uuid, closed.uuid), StageBackend.emptied)
        } finally {
            StageBackend.stored.clear()
            StageBackend.files.clear()
            StageBackend.emptied.clear()
        }
    }
}
