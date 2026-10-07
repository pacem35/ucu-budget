package com.pacemckinney.tally.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pacemckinney.tally.TallyApp
import com.pacemckinney.tally.data.LinkedItem
import com.pacemckinney.tally.engine.Account
import com.pacemckinney.tally.engine.AccountRole
import com.pacemckinney.tally.engine.Kind
import com.pacemckinney.tally.engine.Categories
import com.pacemckinney.tally.engine.Classifier
import com.pacemckinney.tally.engine.InsightEngine
import com.pacemckinney.tally.engine.Recurring
import com.pacemckinney.tally.engine.Snapshot
import com.pacemckinney.tally.engine.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

data class UiState(
    val loaded: Boolean = false,
    val hasKeys: Boolean = false,
    val items: List<LinkedItem> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val txns: List<Txn> = emptyList(),
    val snapshot: Snapshot? = null,
    /** Average monthly spend per category over the last 3 full months (for budget hints). */
    val avgByCategory: Map<String, Double> = emptyMap(),
    val kinds: Map<String, com.pacemckinney.tally.engine.Kind> = emptyMap(),
    /** Extra context per transaction, e.g. "to Share/Savings" for a savings transfer. */
    val notes: Map<String, String> = emptyMap(),
    /** Short account label per account id, shown on transactions when you track several. */
    val accountLabels: Map<String, String> = emptyMap(),
    val savingsGoal: Double = 0.0,
    /** Transaction count per account, including untracked ones (shown in the account manager). */
    val txnCounts: Map<String, Int> = emptyMap(),
    val lastSync: Long = 0,
    val syncing: Boolean = false,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = TallyApp.repo(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch { repo.version.collect { reload() } }
    }

    private suspend fun reload() {
        val s = withContext(Dispatchers.Default) {
            val accounts = repo.db.accounts()
            val txns = repo.db.txns(LocalDate.now().minusMonths(13))
            val snap = repo.snapshot()
            val c = Classifier(txns, accounts)
            val month = YearMonth.now()
            val avg = HashMap<String, Double>()
            for (m in 1..3L) {
                val ym = month.minusMonths(m)
                InsightEngine.spendByCategory(txns, c, ym.atDay(1), ym.atEndOfMonth()).forEach {
                    avg[it.category] = (avg[it.category] ?: 0.0) + it.amount / 3
                }
            }
            _state.value.copy(
                loaded = true,
                hasKeys = repo.store.hasKeys,
                items = repo.store.items,
                accounts = accounts,
                // Transactions on accounts you don't track are left out of the app entirely.
                txns = txns.filter { c.kind(it) != Kind.HIDDEN },
                snapshot = snap,
                avgByCategory = avg,
                kinds = txns.associate { it.id to c.kind(it) },
                notes = txns.mapNotNull { t ->
                    val other = c.counterpartOf(t)?.displayName
                    val note = when (c.kind(t)) {
                        Kind.SAVINGS -> if (t.amount > 0) "Saved" + (other?.let { " → $it" } ?: "") else "From savings" + (other?.let { " ($it)" } ?: "")
                        Kind.LOAN_PAYMENT -> if (other != null) "Loan payment → $other" else "Debt payment"
                        Kind.INTERNAL -> if (other != null) (if (t.amount > 0) "To $other" else "From $other") else "Between your accounts"
                        Kind.REFUND -> "Refund · " + Categories.label(t.effectiveCategory)
                        Kind.EXCLUDED -> "Excluded"
                        else -> null
                    }
                    note?.let { t.id to it }
                }.toMap(),
                accountLabels = accounts.filter { it.included }.takeIf { it.size > 1 }
                    ?.associate { a -> a.id to (a.nickname?.takeIf { it.isNotBlank() } ?: a.mask?.let { "••$it" } ?: a.cleanName) } ?: emptyMap(),
                savingsGoal = repo.prefs.savingsGoal,
                txnCounts = txns.groupingBy { it.accountId }.eachCount(),
                lastSync = repo.prefs.lastSync,
            )
        }
        _state.value = s.copy(syncing = _state.value.syncing, message = _state.value.message)
    }

    fun refresh(live: Boolean = true) {
        if (_state.value.syncing) return
        _state.value = _state.value.copy(syncing = true)
        viewModelScope.launch {
            val r = try { repo.sync(liveBalances = live) } catch (e: Exception) { null }
            _state.value = _state.value.copy(
                syncing = false,
                message = when {
                    r == null -> "Sync failed"
                    r.errors.isNotEmpty() -> r.errors.joinToString("\n")
                    else -> null
                },
            )
        }
    }

    fun toast(msg: String?) { _state.value = _state.value.copy(message = msg) }

    fun saveKeys(clientId: String, secret: String, env: String) {
        repo.store.clientId = clientId
        repo.store.secret = secret
        repo.store.environment = env
        repo.changed()
    }

    fun setCategory(txn: Txn, category: String?, allFromMerchant: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (allFromMerchant && category != null) {
                val key = Recurring.key(txn)
                repo.db.setOverrideWhere(category) { Recurring.key(it) == key }
            } else {
                repo.db.setOverride(txn.id, category)
            }
            repo.changed()
        }
    }

    fun setBudget(category: String, limit: Double?) {
        viewModelScope.launch(Dispatchers.IO) { repo.db.setBudget(category, limit); repo.changed() }
    }

    fun setAccount(a: Account, included: Boolean, role: AccountRole, nickname: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            // Store null when the role matches the default, so a fix to the default carries through.
            val override = role.takeIf { it != AccountRole.default(a.type, a.subtype) }
            repo.db.setAccountPrefs(a.id, included, override, nickname)
            repo.changed()
        }
    }

    fun markAccountsReviewed() {
        viewModelScope.launch(Dispatchers.IO) { repo.db.markAccountsReviewed(repo.db.accounts()); repo.changed() }
    }

    fun setSavingsGoal(v: Double?) {
        repo.prefs.savingsGoal = v ?: 0.0
        repo.changed()
    }

    /** Report for the export screen; reads straight from the database so it's never stale. */
    suspend fun buildReport(months: Int, purchase: com.pacemckinney.tally.engine.PlannedPurchase?) =
        withContext(Dispatchers.Default) {
            val today = LocalDate.now()
            com.pacemckinney.tally.engine.ReportBuilder.build(
                repo.db.txns(today.minusMonths(months + 2L).withDayOfMonth(1)), repo.db.accounts(), today, months, purchase,
            )
        }

    fun institutionName(): String = repo.store.items.map { it.institution }.distinct().joinToString(", ").ifBlank { "United Credit Union" }

    fun disconnect(itemId: String) {
        viewModelScope.launch { repo.disconnect(itemId) }
    }

    fun resetEverything() {
        viewModelScope.launch(Dispatchers.IO) { repo.resetEverything() }
    }

    fun settingsChanged() = repo.changed()

    companion object {
        val budgetChoices get() = Categories.budgetable
    }
}
