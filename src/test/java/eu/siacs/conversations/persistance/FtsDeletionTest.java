package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class FtsDeletionTest {
    private static String statement(String name) throws Exception {
        final var field = DatabaseBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
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
