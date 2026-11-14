package eu.siacs.conversations.utils;

import android.database.AbstractWindowedCursor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.sqlite.SQLiteCursor;

public class CursorUtils {

    public static void upgradeCursorWindowSize(final Cursor cursor) {
        // Tatrak: SQLCipher cursors have their own window hierarchy.
        if (cursor instanceof net.zetetic.database.sqlcipher.SQLiteCursor encrypted) {
            encrypted.setWindow(new net.zetetic.database.CursorWindow("4M", 4 * 1024 * 1024));
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            if (cursor instanceof AbstractWindowedCursor windowedCursor) {
                windowedCursor.setWindow(new CursorWindow("4M", 4 * 1024 * 1024));
            }
            if (cursor instanceof SQLiteCursor) {
                ((SQLiteCursor) cursor).setFillWindowForwardOnly(true);
            }
        }
    }
}
