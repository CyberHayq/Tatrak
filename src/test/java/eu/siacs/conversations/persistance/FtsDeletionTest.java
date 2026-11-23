package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import eu.siacs.conversations.Config;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@org.robolectric.annotation.Config(application = Application.class, sdk = 35)
public class FtsDeletionTest {
    private static String statement(String name) throws Exception {
        final var field = DatabaseBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    public void accountScopeFiltersBeforeTheFtsLimit() throws Exception {
        try (var db = SQLiteDatabase.create(null)) {
            db.execSQL("CREATE TABLE conversations(uuid TEXT PRIMARY KEY, contactjid TEXT, accountUuid TEXT, mode INTEGER)");
            db.execSQL("CREATE TABLE messages(uuid TEXT PRIMARY KEY, body TEXT, conversationUuid TEXT, timeSent INTEGER, encryption INTEGER, type INTEGER)");
            db.execSQL(statement("CREATE_MESSAGE_INDEX_TABLE"));
            db.execSQL(statement("CREATE_MESSAGE_INSERT_TRIGGER"));
            db.execSQL("INSERT INTO conversations VALUES('chat-a','peer@example.com','a',0),('chat-b','other@example.com','b',0)");
            db.execSQL("INSERT INTO messages VALUES('old','needle','chat-a',0,0,0)");
            db.execSQL("WITH RECURSIVE newer(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM newer WHERE i < ?)"
                    + " INSERT INTO messages SELECT 'b-' || i, 'needle', 'chat-b', i, 0, 0 FROM newer",
                    new Object[] {Config.MAX_SEARCH_RESULTS + 1});

            final var global = DatabaseBackend.messageSearchQuery(List.of("needle"), null, null);
            try (var cursor = db.rawQuery(global.first, global.second)) {
                assertEquals(Config.MAX_SEARCH_RESULTS, cursor.getCount());
                while (cursor.moveToNext()) assertEquals("chat-b", cursor.getString(2));
            }
            final var scoped = DatabaseBackend.messageSearchQuery(List.of("needle"), null, "a");
            try (var cursor = db.rawQuery(scoped.first, scoped.second)) {
                assertEquals(1, cursor.getCount());
                cursor.moveToFirst();
                assertEquals("old", cursor.getString(0));
            }
            final var inChat = DatabaseBackend.messageSearchQuery(List.of("needle"), "chat-a", "b");
            try (var cursor = db.rawQuery(inChat.first, inChat.second)) {
                assertEquals(1, cursor.getCount());
            }
        }
    }

    @Test public void deletingAccountKeepsExternalContentIndexValid() throws Exception {
        try (var db = SQLiteDatabase.create(null)) {
            db.execSQL("PRAGMA foreign_keys=ON");
            db.execSQL("CREATE TABLE accounts(uuid TEXT PRIMARY KEY)");
            db.execSQL("CREATE TABLE messages(uuid TEXT PRIMARY KEY,body TEXT,account TEXT REFERENCES accounts(uuid) ON DELETE CASCADE)");
            db.execSQL(statement("CREATE_MESSAGE_INDEX_TABLE"));
            db.execSQL(statement("CREATE_MESSAGE_INSERT_TRIGGER"));
            db.execSQL(statement("CREATE_MESSAGE_DELETE_TRIGGER"));
            db.execSQL("INSERT INTO accounts VALUES('fixture')");
            db.execSQL("INSERT INTO messages VALUES('m1','searchable history','fixture')");
            db.execSQL("DELETE FROM accounts WHERE uuid='fixture'");
            try (var cursor = db.rawQuery("PRAGMA integrity_check", null)) {
                cursor.moveToFirst();
                assertEquals("ok", cursor.getString(0));
            }
            db.execSQL("INSERT INTO messages_index(messages_index) VALUES('integrity-check')");
        }
    }
}
