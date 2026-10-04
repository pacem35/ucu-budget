package com.pacemckinney.tally.ui

import android.text.format.DateUtils
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
                    Label("Available in checking")
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
                            Label("Set aside for bills before then · ${money(safe.billsBeforeHorizon)}")
                            Spacer(Modifier.height(4.dp))
                            safe.bills.take(6).forEach { (bill, date) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CategoryBadge(bill.category, size = 26)
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
                    Row(Modifier.fillMaxWidth()) {
                        Stat("In", money(s.incomeMtd), tones.income, Modifier.weight(1f))
                        Stat("Out", money(s.spendMtd), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        Stat("Net", (if (s.net >= 0) "+" else "") + money(s.net), if (s.net >= 0) tones.income else tones.alert, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))
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

            // Accounts.
            if (state.accounts.isNotEmpty()) item {
                Section(title = "Accounts") {
                    state.accounts.forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Label(listOfNotNull(a.subtype?.replaceFirstChar { it.uppercase() }, a.mask?.let { "••$it" }).joinToString(" · "))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(a.current ?: 0.0), style = MaterialTheme.typography.bodyLarge.tabular(), fontWeight = FontWeight.Medium)
                                if (a.available != null && a.available != a.current) Label("${money(a.available)} available")
                            }
                        }
                    }
                }
            }

            if (state.items.isEmpty()) item {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Label(label)
        Text(value, style = MaterialTheme.typography.titleLarge.tabular(), fontWeight = FontWeight.SemiBold, color = color,
            maxLines = 1)
    }
}
