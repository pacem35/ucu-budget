package com.pacemckinney.tally.data

import android.content.Context
import com.pacemckinney.tally.engine.Account
import com.pacemckinney.tally.engine.InsightEngine
import com.pacemckinney.tally.engine.Snapshot
import com.pacemckinney.tally.engine.Txn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** Non-secret settings. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var syncMinutes: Int
        get() = p.getInt("syncMinutes", 60)
        set(v) = p.edit().putInt("syncMinutes", v).apply()
    var notifyEveryTxn: Boolean
        get() = p.getBoolean("notifyEveryTxn", false)
        set(v) = p.edit().putBoolean("notifyEveryTxn", v).apply()
    var notifyThreshold: Double
        get() = p.getFloat("notifyThreshold", 50f).toDouble()
        set(v) = p.edit().putFloat("notifyThreshold", v.toFloat()).apply()
    var largeThreshold: Double
        get() = p.getFloat("largeThreshold", 200f).toDouble()
        set(v) = p.edit().putFloat("largeThreshold", v.toFloat()).apply()
    var lowBalance: Double
        get() = p.getFloat("lowBalance", 100f).toDouble()
        set(v) = p.edit().putFloat("lowBalance", v.toFloat()).apply()
    var notifyIncome: Boolean
        get() = p.getBoolean("notifyIncome", true)
        set(v) = p.edit().putBoolean("notifyIncome", v).apply()
    var notifyAlerts: Boolean
        get() = p.getBoolean("notifyAlerts", true)
        set(v) = p.edit().putBoolean("notifyAlerts", v).apply()
    var biometricLock: Boolean
        get() = p.getBoolean("biometricLock", true)
        set(v) = p.edit().putBoolean("biometricLock", v).apply()
    /** Monthly amount you want to move into savings; 0 = no goal. */
    var savingsGoal: Double
        get() = p.getFloat("savingsGoal", 0f).toDouble()
        set(v) = p.edit().putFloat("savingsGoal", v.toFloat()).apply()
    /** Set once history has been re-pulled with raw bank descriptions (needed for UCU account codes). */
    var refetchedDescriptions: Boolean
        get() = p.getBoolean("refetchedDescriptions", false)
        set(v) = p.edit().putBoolean("refetchedDescriptions", v).apply()
    /** Name printed on exported reports. */
    var reportName: String
        get() = p.getString("reportName", "") ?: ""
        set(v) = p.edit().putString("reportName", v).apply()
    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) = p.edit().putLong("lastSync", v).apply()

    /** Alerts already sent (so a budget warning fires once a month, not every sync). */
    fun alreadyNotified(key: String): Boolean = p.getBoolean("n:$key", false)
    fun markNotified(key: String) = p.edit().putBoolean("n:$key", true).apply()
}

data class SyncResult(val newTxns: List<Txn>, val errors: List<String>)

class Repo(private val context: Context) {
    val store = SecureStore(context)
    val db = Db(context)
    val prefs = Prefs(context)
    private val mutex = Mutex()

    private val _version = MutableStateFlow(0L)
    /** Bumps whenever stored data changes, so screens know to reload. */
    val version: StateFlow<Long> = _version
    fun changed() { _version.value = _version.value + 1 }

    fun api(): PlaidApi? {
        val id = store.clientId ?: return null
        val s = store.secret ?: return null
        if (id.isBlank() || s.isBlank()) return null
        return PlaidApi(id, s, store.environment)
    }

    private fun requireApi() = api() ?: throw IllegalStateException("Add your Plaid keys in Settings first.")

    suspend fun createLinkToken(updateItemId: String? = null): String {
        val token = updateItemId?.let { id -> store.items.firstOrNull { it.itemId == id }?.accessToken }
        return requireApi().createLinkToken(context.packageName, token)
    }

    suspend fun completeLink(publicToken: String, institution: String?) {
        val api = requireApi()
        val (access, itemId) = api.exchangePublicToken(publicToken)
        val name = institution ?: api.institutionName(access) ?: "Bank"
        store.items = store.items.filterNot { it.itemId == itemId } + LinkedItem(itemId, access, name, null)
        sync()
    }

    fun clearReauth(itemId: String) = store.updateItem(itemId) { it.copy(needsReauth = false, lastError = null) }

    suspend fun disconnect(itemId: String) {
        val item = store.items.firstOrNull { it.itemId == itemId } ?: return
        try { api()?.removeItem(item.accessToken) } catch (_: Exception) { }
        store.items = store.items.filterNot { it.itemId == itemId }
        db.deleteItemData(itemId)
        changed()
    }

    /**
     * Pull everything new from Plaid. [liveBalances] asks UCU for up-to-the-second balances
     * (used on pull-to-refresh); background syncs use Plaid's cached balances.
     */
    suspend fun sync(liveBalances: Boolean = false): SyncResult = mutex.withLock {
        val api = api() ?: return SyncResult(emptyList(), listOf("No Plaid keys"))
        if (!prefs.refetchedDescriptions) {
            // Starting the sync over re-sends your whole history (free; no new Plaid connection),
            // this time with UCU's original descriptions like "Transfer to L1201".
            store.items = store.items.map { it.copy(cursor = null) }
            prefs.refetchedDescriptions = true
        }
        val fresh = ArrayList<Txn>()
        val errors = ArrayList<String>()
        for (item in store.items) {
            try {
                val startCursor = item.cursor
                var attempt = 0
                while (true) {
                    try {
                        val added = ArrayList<Txn>(); val modified = ArrayList<Txn>(); val removed = ArrayList<String>()
                        var cursor = startCursor
                        do {
                            val page = api.syncPage(item.accessToken, cursor)
                            added += page.added; modified += page.modified; removed += page.removed
                            cursor = page.nextCursor
                        } while (page.hasMore)
                        val known = db.txns(LocalDate.now().minusDays(30)).map { it.id }.toHashSet()
                        db.upsertTxns(added + modified)
                        db.deleteTxns(removed)
                        // Only alert on genuinely new activity, not the first two-year backfill.
                        if (startCursor != null) fresh += added.filter { it.id !in known && it.date >= LocalDate.now().minusDays(3) }
                        store.updateItem(item.itemId) { it.copy(cursor = cursor, needsReauth = false, lastError = null) }
                        break
                    } catch (e: PlaidException) {
                        if (e.errorCode == "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION" && attempt++ < 3) continue
                        throw e
                    }
                }
                val accounts: List<Account> = if (liveBalances) {
                    try { api.liveBalances(item.accessToken, item.itemId) } catch (e: PlaidException) {
                        if (e.needsReauth) throw e
                        api.accounts(item.accessToken, item.itemId)
                    }
                } else api.accounts(item.accessToken, item.itemId)
                db.replaceAccounts(item.itemId, accounts)
            } catch (e: PlaidException) {
                errors += "${item.institution}: ${e.message}"
                store.updateItem(item.itemId) { it.copy(needsReauth = it.needsReauth || e.needsReauth, lastError = e.message) }
            } catch (e: Exception) {
                errors += "${item.institution}: ${e.message ?: "network error"}"
                store.updateItem(item.itemId) { it.copy(lastError = e.message ?: "network error") }
            }
        }
        if (errors.size < store.items.size || store.items.isEmpty()) prefs.lastSync = System.currentTimeMillis()
        changed()
        SyncResult(fresh, errors)
    }

    fun snapshot(today: LocalDate = LocalDate.now()): Snapshot = InsightEngine.build(
        txns = db.txns(today.minusMonths(7).withDayOfMonth(1)),
        accounts = db.accounts(),
        budgets = db.budgets(),
        today = today,
        largeTxnThreshold = prefs.largeThreshold,
        lowBalanceThreshold = prefs.lowBalance,
        savingsGoal = prefs.savingsGoal.takeIf { it > 0 },
    )

    fun resetEverything() {
        store.clearAll()
        db.wipe()
        changed()
    }
}
