package am.cyberhayq.chat.storage.media

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Platform SQLite (Robolectric) behind the `SupportSQLiteDatabase` contract that SQLCipher's
 * database implements in the app, as `ImportBackupWorkerTest` does. Queries take text arguments only.
 */
fun supportDatabase(db: SQLiteDatabase): SupportSQLiteDatabase =
    Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
        try {
            if (method.name == "query" && args[0] is String) {
                db.rawQuery(args[0] as String, (args.getOrNull(1) as Array<*>?)?.map { it.toString() }?.toTypedArray())
            } else {
                db.javaClass.getMethod(method.name, *method.parameterTypes).invoke(db, *(args ?: emptyArray()))
            }
        } catch (e: InvocationTargetException) { throw e.cause ?: e }
    } as SupportSQLiteDatabase
