package com.pacemckinney.tally.ui

import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import com.pacemckinney.tally.engine.Account
import com.pacemckinney.tally.engine.AccountRole
import com.pacemckinney.tally.engine.CategorySpend
import com.pacemckinney.tally.engine.MonthFlow
import com.pacemckinney.tally.engine.Snapshot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pacemckinney.tally.engine.Categories
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val shortDay = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onReauth: (String) -> Unit,
    onSeeInsights: () -> Unit,
    onSeeBudgets: () -> Unit,
    onManageAccounts: () -> Unit,
    onExport: () -> Unit,
) {
    val s = state.snapshot
    val tones = LocalTones.current
    PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: what you have right now.
            item {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Available in checking", Modifier.weight(1f))
                        TextButton(onClick = onExport) {
                            Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export PDF")
                        }
                    }
                    Text(money(s?.totalSpendable ?: 0.0), style = MaterialTheme.typography.displaySmall.tabular(),
                        fontWeight = FontWeight.SemiBold)
                    val ago = if (state.lastSync == 0L) "Not synced yet"
                    else "Updated " + DateUtils.getRelativeTimeSpanString(state.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    Label(if (state.syncing) "Syncing with UCU…" else "$ago · pull down to refresh")
                }
            }

            state.items.filter { it.needsReauth }.forEach { li ->
                item(key = "reauth-${li.itemId}") {
                    Surface(color = tones.alert.copy(alpha = 0.12f), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${li.institution} needs you to sign in again", fontWeight = FontWeight.Medium)
                                Text("Updates are paused until you do.", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { onReauth(li.itemId) }) { Text("Fix") }
                        }
                    }
                }
            }

            // Plaid hands over every account the login can see; ask which ones are really his.
            val unreviewed = state.accounts.count { !it.reviewed }
            if (unreviewed > 0 && state.accounts.size > 1) item(key = "review") {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Are all ${state.accounts.size} accounts yours?", fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Turn off ones that aren't, and check which are savings, cards and loans.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onManageAccounts) { Text("Review") }
                    }
                }
            }

            if (s == null) return@LazyColumn

            if (state.txns.isEmpty()) item {
                Section {
                    Text("Pulling your history from UCU…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("The first sync can take a few minutes. Tally will check back on its own; you can also pull down to refresh.",
                        style = MaterialTheme.typography.bodyMedium, color = tones.muted)
                }
            }

            // Safe to spend.
            s.safeToSpend?.let { safe ->
                item {
                    Section(title = "Safe to spend") {
                        val perDay = safe.perDay(s.today)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(money(perDay), style = MaterialTheme.typography.headlineLarge.tabular(), fontWeight = FontWeight.SemiBold,
                                color = if (safe.amount < 0) tones.alert else MaterialTheme.colorScheme.onSurface)
                            Text(" / day", style = MaterialTheme.typography.titleMedium, color = tones.muted,
                                modifier = Modifier.padding(bottom = 4.dp))
                        }
                        val until = if (safe.horizonIsPayday) "payday, ${safe.horizon.format(shortDay)}" else "month end"
                        val days = ChronoUnit.DAYS.between(s.today, safe.horizon).coerceAtLeast(1)
                        Text("${money(safe.amount)} for the next $days day${if (days == 1L) "" else "s"}, until $until",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (safe.bills.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Label("Set aside before then · ${money(safe.billsBeforeHorizon)}")
                            Spacer(Modifier.height(4.dp))
                            safe.bills.take(8).forEach { (bill, date) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    KindBadge(bill.kind, bill.category, 26)
                                    Spacer(Modifier.width(10.dp))
                                    Text(bill.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(date.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)), style = MaterialTheme.typography.bodySmall,
                                        color = tones.muted)
                                    Spacer(Modifier.width(12.dp))
                                    Text(money(bill.typicalAmount), style = MaterialTheme.typography.bodyMedium.tabular())
                                }
                            }
                        }
                    }
                }
            }

            // This month.
            item {
                Section(title = s.month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)) {
                    FlowBreakdown(s.thisMonth, s.incomeSources)
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Label("Spending pace")
                    Spacer(Modifier.height(8.dp))
                    PaceChart(s.cumulativeThisMonth, s.cumulativeLastMonth, s.month.lengthOfMonth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendDot(MaterialTheme.colorScheme.primary, "This month")
                        LegendDot(tones.chartPrev, "Last month", dashed = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Heading for about ${money(s.projectedMonthSpend)} in spending" +
                        if (s.spendLastMonthTotal > 0) " (last month ${money(s.spendLastMonthTotal)})" else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Top insights.
            if (s.insights.isNotEmpty()) item {
                Section(title = "Insights", action = { TextButton(onClick = onSeeInsights) { Text("All ${s.insights.size}") } }) {
                    s.insights.filter { it.id != "safe" }.take(3).forEach { InsightRow(it) }
                }
            }

            // Where it went.
            if (s.byCategory.isNotEmpty()) item {
                Section(title = "Where it went", action = { TextButton(onClick = onSeeBudgets) { Text("Budgets") } }) {
                    val top = s.byCategory.take(7)
                    val max = top.first().amount
                    top.forEach { c ->
                        val budget = s.budgets.firstOrNull { it.budget.category == c.category }
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(c.category, size = 30)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(Categories.label(c.category), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(money(c.amount) + (budget?.let { " / " + money(it.budget.monthlyLimit) } ?: ""),
                                        style = MaterialTheme.typography.bodyMedium.tabular())
                                }
                                Spacer(Modifier.height(4.dp))
                                val frac = budget?.fraction?.toFloat() ?: (c.amount / max).toFloat()
                                val color = when {
                                    budget == null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    budget.fraction >= 1 -> tones.alert
                                    budget.fraction >= 0.8 -> tones.warn
                                    else -> MaterialTheme.colorScheme.primary
                                }
                                Bar(frac, color)
                            }
                        }
                    }
                }
            }

            // Accounts, grouped by what they're for.
            if (state.accounts.isNotEmpty()) item {
                AccountsSection(state.accounts, s, onManageAccounts)
            }

            if (state.items.isEmpty()) item {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
            }
        }
    }
}

@Composable
fun KindBadge(kind: com.pacemckinney.tally.engine.Kind?, category: String, size: Int = 30) {
    val tint = kindColor(kind)
    androidx.compose.foundation.layout.Box(
        Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(kindIcon(kind, category), null, tint = tint, modifier = Modifier.size((size * 0.55).dp)) }
}

/** Where this month's income went: spending, loans, savings, and what's left. */
@Composable
private fun FlowBreakdown(f: MonthFlow, sources: List<CategorySpend>) {
    val tones = LocalTones.current
    val spendColor = MaterialTheme.colorScheme.secondary
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Label("Income")
            Text(money(f.income), style = MaterialTheme.typography.headlineSmall.tabular(), fontWeight = FontWeight.SemiBold,
                color = tones.income)
        }
        Column(horizontalAlignment = Alignment.End) {
            Label(if (f.leftOver >= 0) "Left over" else "Over by")
            Text(money(kotlin.math.abs(f.leftOver)), style = MaterialTheme.typography.headlineSmall.tabular(),
                fontWeight = FontWeight.SemiBold, color = if (f.leftOver >= 0) MaterialTheme.colorScheme.onSurface else tones.alert)
        }
    }
    if (sources.size > 1) Label(sources.take(3).joinToString(" · ") { "${Categories.label(it.category)} ${money(it.amount)}" })
    Spacer(Modifier.height(12.dp))
    val outflow = f.spending + f.loanPayments + maxOf(f.saved, 0.0)
    SegmentBar(
        listOf(f.spending to spendColor, f.loanPayments to tones.loan, maxOf(f.saved, 0.0) to tones.savings),
        total = maxOf(f.income, outflow, 1.0),
    )
    Spacer(Modifier.height(12.dp))
    FlowLine(spendColor, "Spending", f.spending, f.income)
    FlowLine(tones.loan, "Loans & debt", f.loanPayments, f.income)
    FlowLine(tones.savings, if (f.saved >= 0) "Saved" else "Pulled from savings", f.saved, f.income)
}

@Composable
private fun FlowLine(color: androidx.compose.ui.graphics.Color, label: String, amount: Double, income: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (income > 0 && amount > 0) Label("${(amount / income * 100).toInt()}%  ", Modifier)
        Text(money(amount), style = MaterialTheme.typography.bodyMedium.tabular(), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AccountsSection(all: List<Account>, s: Snapshot, onManage: () -> Unit) {
    val tones = LocalTones.current
    val tracked = all.filter { it.included }
    val hidden = all.size - tracked.size
    Section(title = "Accounts", action = { TextButton(onClick = onManage) { Text("Manage") } }) {
        val b = s.balances
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Label("You have")
                Text(money(b.cash + b.savings), style = MaterialTheme.typography.titleLarge.tabular(), fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Label("You owe")
                Text(money(b.creditOwed + b.loansOwed), style = MaterialTheme.typography.titleLarge.tabular(), fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Label("Net")
                Text(money(b.netWorth), style = MaterialTheme.typography.titleLarge.tabular(), fontWeight = FontWeight.SemiBold,
                    color = if (b.netWorth >= 0) tones.income else tones.alert)
            }
        }
        val groups = listOf(
            AccountRole.SPENDING to "Checking",
            AccountRole.SAVINGS to "Savings",
            AccountRole.CREDIT to "Credit cards",
            AccountRole.LOAN to "Loans",
        )
        for ((role, title) in groups) {
            val list = tracked.filter { it.role == role }
            if (list.isEmpty()) continue
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Label(title.uppercase(), Modifier.weight(1f))
                val total = when (role) {
                    AccountRole.SPENDING -> b.cash
                    AccountRole.SAVINGS -> b.savings
                    AccountRole.CREDIT -> b.creditOwed
                    AccountRole.LOAN -> b.loansOwed
                }
                Label(money(total) + if (role == AccountRole.CREDIT || role == AccountRole.LOAN) " owed" else "")
            }
            list.forEach { a -> AccountLine(a, s) }
        }
        if (hidden > 0) {
            Spacer(Modifier.height(10.dp))
            Label("$hidden account${if (hidden == 1) "" else "s"} not tracked", Modifier.clickable(onClick = onManage))
        }
    }
}

@Composable
private fun AccountLine(a: Account, s: Snapshot) {
    val tones = LocalTones.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (a.role) {
                    AccountRole.CREDIT -> a.limit?.let { "${money(it - a.owed)} available of ${money(it)}" }
                    AccountRole.LOAN -> s.loans.firstOrNull { it.account.id == a.id }?.let { l ->
                        listOfNotNull(
                            if (l.monthlyPayment > 1) "~${money(l.monthlyPayment)}/mo" else null,
                            l.monthsLeft?.let { "about $it payments left" },
                        ).joinToString(" · ").ifEmpty { null }
                    }
                    else -> a.available?.takeIf { it != a.current }?.let { "${money(it)} available" }
                }
                Label(listOfNotNull(a.mask?.let { "••$it" }, sub).joinToString(" · "))
            }
            Text(money(if (a.role == AccountRole.SPENDING) a.spendable else a.current ?: 0.0),
                style = MaterialTheme.typography.bodyLarge.tabular(), fontWeight = FontWeight.Medium,
                color = if (a.role == AccountRole.CREDIT || a.role == AccountRole.LOAN) MaterialTheme.colorScheme.onSurface else tones.income)
        }
        a.utilization?.let { u ->
            Spacer(Modifier.height(6.dp))
            Bar(u.toFloat(), when {
                u >= 0.9 -> tones.alert
                u >= 0.3 -> tones.warn
                else -> MaterialTheme.colorScheme.primary
            })
        }
    }
}
