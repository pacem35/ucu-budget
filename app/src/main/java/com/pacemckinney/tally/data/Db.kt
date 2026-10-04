package com.pacemckinney.tally.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pacemckinney.tally.engine.Account
import com.pacemckinney.tally.engine.Budget
import com.pacemckinney.tally.engine.Txn
import java.time.LocalDate

/** Local copy of your accounts and transactions, plus your budgets and category edits. */
class Db(context: Context) : SQLiteOpenHelper(context, "tally.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE accounts(id TEXT PRIMARY KEY, item_id TEXT, name TEXT, mask TEXT, type TEXT,
               subtype TEXT, current REAL, available REAL, credit_limit REAL)""",
        )
        db.execSQL(
            """CREATE TABLE txns(id TEXT PRIMARY KEY, account_id TEXT, amount REAL, date TEXT, name TEXT,
               merchant TEXT, category TEXT, detailed TEXT, pending INTEGER, logo_url TEXT)""",
        )
        db.execSQL("CREATE INDEX txns_date ON txns(date)")
        // Kept separate so a re-sync from Plaid never wipes your edits.
        db.execSQL("CREATE TABLE overrides(txn_id TEXT PRIMARY KEY, category TEXT)")
        db.execSQL("CREATE TABLE budgets(category TEXT PRIMARY KEY, monthly_limit REAL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun upsertTxns(list: List<Txn>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in list) {
                db.insertWithOnConflict("txns", null, ContentValues().apply {
                    put("id", t.id); put("account_id", t.accountId); put("amount", t.amount)
                    put("date", t.date.toString()); put("name", t.name); put("merchant", t.merchant)
                    put("category", t.category); put("detailed", t.detailed)
                    put("pending", if (t.pending) 1 else 0); put("logo_url", t.logoUrl)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteTxns(ids: List<String>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("txns", "id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteItemData(itemId: String) {
        val db = writableDatabase
        db.execSQL("DELETE FROM txns WHERE account_id IN (SELECT id FROM accounts WHERE item_id=?)", arrayOf(itemId))
        db.delete("accounts", "item_id=?", arrayOf(itemId))
    }

    fun txns(since: LocalDate? = null): List<Txn> {
        val where = if (since != null) "WHERE t.date >= ?" else ""
        val args = if (since != null) arrayOf(since.toString()) else null
        readableDatabase.rawQuery(
            """SELECT t.id, t.account_id, t.amount, t.date, t.name, t.merchant, t.category, t.detailed,
                      t.pending, t.logo_url, o.category
               FROM txns t LEFT JOIN overrides o ON o.txn_id = t.id $where
               ORDER BY t.date DESC, t.pending DESC, t.id""",
            args,
        ).use { c ->
            val out = ArrayList<Txn>(c.count)
            while (c.moveToNext()) {
                out += Txn(
                    id = c.getString(0), accountId = c.getString(1), amount = c.getDouble(2),
                    date = LocalDate.parse(c.getString(3)), name = c.getString(4) ?: "",
                    merchant = c.getString(5), category = c.getString(6) ?: "OTHER", detailed = c.getString(7),
                    pending = c.getInt(8) == 1, logoUrl = c.getString(9), userCategory = c.getString(10),
                )
            }
            return out
        }
    }

    fun txnCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM txns", null).use { it.moveToFirst(); it.getInt(0) }

    @Synchronized
    fun replaceAccounts(itemId: String, list: List<Account>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("accounts", "item_id=?", arrayOf(itemId))
            for (a in list) db.insert("accounts", null, ContentValues().apply {
                put("id", a.id); put("item_id", a.itemId); put("name", a.name); put("mask", a.mask)
                put("type", a.type); put("subtype", a.subtype); put("current", a.current)
                put("available", a.available); put("credit_limit", a.creditLimit)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun accounts(): List<Account> = readableDatabase.rawQuery(
        "SELECT id, item_id, name, mask, type, subtype, current, available, credit_limit FROM accounts ORDER BY type, name", null,
    ).use { c ->
        val out = ArrayList<Account>()
        while (c.moveToNext()) out += Account(
            c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
            if (c.isNull(6)) null else c.getDouble(6), if (c.isNull(7)) null else c.getDouble(7),
            if (c.isNull(8)) null else c.getDouble(8),
        )
        out
    }

    @Synchronized
    fun setOverride(txnId: String, category: String?) {
        if (category == null) writableDatabase.delete("overrides", "txn_id=?", arrayOf(txnId))
        else writableDatabase.insertWithOnConflict("overrides", null,
            ContentValues().apply { put("txn_id", txnId); put("category", category) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Re-label every transaction from the same merchant (e.g. always count "VENMO" as Food). */
    @Synchronized
    fun setOverrideWhere(category: String, matcher: (Txn) -> Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            txns().filter(matcher).forEach {
                db.insertWithOnConflict("overrides", null,
                    ContentValues().apply { put("txn_id", it.id); put("category", category) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun budgets(): List<Budget> = readableDatabase.rawQuery("SELECT category, monthly_limit FROM budgets", null).use { c ->
        val out = ArrayList<Budget>()
        while (c.moveToNext()) out += Budget(c.getString(0), c.getDouble(1))
        out
    }

    @Synchronized
    fun setBudget(category: String, limit: Double?) {
        if (limit == null || limit <= 0) writableDatabase.delete("budgets", "category=?", arrayOf(category))
        else writableDatabase.insertWithOnConflict("budgets", null,
            ContentValues().apply { put("category", category); put("monthly_limit", limit) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun wipe() {
        val db = writableDatabase
        listOf("accounts", "txns", "overrides", "budgets").forEach { db.delete(it, null, null) }
    }
}
