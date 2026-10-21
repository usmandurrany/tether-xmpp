package io.github.usmandurrany.tether.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.usmandurrany.tether.core.BareJid
import io.github.usmandurrany.tether.im.Message
import io.github.usmandurrany.tether.im.MessageCodec
import io.github.usmandurrany.tether.im.MessagePosition
import io.github.usmandurrany.tether.im.MessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [MessageStore] in an SQLite database in the app's private storage: the
 * default of [TetherAndroid.chat]. One database per account, with one row per
 * message: the key columns, the timestamp, and the whole message in one text
 * column ([MessageCodec]), so new fields never need a migration. It is
 * [paged][MessageStore.paged]: [TetherChat][io.github.usmandurrany.tether.im.TetherChat]
 * reads each chat's newest messages at startup and older ones a page at a
 * time, through the index on peer and timestamp.
 *
 * Like any app database it is readable only by the app, and not encrypted
 * beyond the device's own storage encryption.
 */
public class SqliteMessageStore(context: Context, account: BareJid) : MessageStore {
    private val helper = Helper(context.applicationContext, databaseName(account))

    override suspend fun load(): List<Message> = io {
        helper.readableDatabase.query(TABLE, arrayOf(DATA), null, null, null, null, null).use { c ->
            buildList { while (c.moveToNext()) MessageCodec.decode(c.getString(0))?.let(::add) }
        }
    }

    override val paged: Boolean get() = true

    override suspend fun chats(): Set<BareJid> = io {
        helper.readableDatabase.query(true, TABLE, arrayOf(PEER), null, null, null, null, null, null).use { c ->
            buildSet { while (c.moveToNext()) runCatching { BareJid.parse(c.getString(0)) }.getOrNull()?.let(::add) }
        }
    }

    override suspend fun loadBefore(peer: BareJid, before: MessagePosition?, limit: Int): List<Message> = io {
        val (where, args) = if (before == null) {
            "$PEER = ?" to arrayOf(peer.toString())
        } else {
            val time = before.timestamp.toString()
            "$PEER = ? AND ($TIMESTAMP < ? OR ($TIMESTAMP = ? AND $KEY < ?))" to arrayOf(peer.toString(), time, time, before.key)
        }
        helper.readableDatabase.query(TABLE, arrayOf(DATA), where, args, null, null, "$TIMESTAMP DESC, $KEY DESC", limit.toString()).use { c ->
            buildList { while (c.moveToNext()) MessageCodec.decode(c.getString(0))?.let(::add) }
        }
    }

    override suspend fun save(messages: List<Message>) {
        if (messages.isEmpty()) return
        io {
            transaction { db ->
                for (m in messages) {
                    val row = ContentValues().apply {
                        put(KEY, MessageCodec.keyOf(m))
                        put(PEER, m.peer.toString())
                        put(TIMESTAMP, m.timestamp)
                        put(DATA, MessageCodec.encode(m))
                    }
                    db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
        }
    }

    override suspend fun delete(messages: List<Message>) {
        if (messages.isEmpty()) return
        io { transaction { db -> messages.forEach { db.delete(TABLE, "$KEY = ?", arrayOf(MessageCodec.keyOf(it))) } } }
    }

    override suspend fun clear() {
        io { helper.writableDatabase.delete(TABLE, null, null) }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE $TABLE ($KEY TEXT PRIMARY KEY NOT NULL, $PEER TEXT NOT NULL, $TIMESTAMP INTEGER NOT NULL, $DATA TEXT NOT NULL)")
            db.execSQL("CREATE INDEX ${TABLE}_peer ON $TABLE ($PEER, $TIMESTAMP)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Version 1 is the first; later versions migrate here.
        }
    }

    private companion object {
        const val VERSION = 1
        const val TABLE = "messages"
        const val KEY = "message_key"
        const val PEER = "peer"
        const val TIMESTAMP = "timestamp"
        const val DATA = "data"

        fun databaseName(account: BareJid): String =
            "tether-messages-" + account.toString().replace(Regex("[^A-Za-z0-9._-]"), "_") + ".db"
    }
}
