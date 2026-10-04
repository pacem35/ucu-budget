package com.pacemckinney.tally.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pacemckinney.tally.engine.RecurringStream
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun InsightsScreen(state: UiState) {
    val s = state.snapshot ?: return
    val tones = LocalTones.current
    val bills = s.recurring.filter { !it.isIncome }
    val income = s.recurring.filter { it.isIncome }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section(title = "What Tally noticed") {
                if (s.insights.isEmpty()) Text("Nothing yet. Insights appear once there's a few weeks of history.", color = tones.muted)
                s.insights.forEach { InsightRow(it) }
            }
        }
        if (income.isNotEmpty()) item {
            Section(title = "Regular income") {
                income.forEach { StreamRow(it) }
            }
        }
        if (bills.isNotEmpty()) item {
            Section(title = "Bills & subscriptions · ${money(bills.sumOf { it.monthlyAmount })}/mo") {
                bills.forEach { StreamRow(it) }
            }
        }
        item {
            Text(
                "Bills and paychecks are detected automatically from repeating charges and deposits. " +
                    "Moving money between your own UCU accounts is ignored in every total. " +
                    "Tap any transaction in Activity to re-categorize it or exclude it.",
                style = MaterialTheme.typography.bodySmall, color = tones.muted, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun StreamRow(r: RecurringStream) {
    val tones = LocalTones.current
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(r.category, if (r.isIncome) tones.income else MaterialTheme.colorScheme.secondary, size = 32)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Label("${r.cadence.label.replaceFirstChar { it.uppercase() }} · next ${r.nextDate.format(fmt)}")
        }
        Column(horizontalAlignment = Alignment.End) {
            Text((if (r.isIncome) "+" else "") + money(r.typicalAmount), style = MaterialTheme.typography.bodyLarge.tabular(),
                color = if (r.isIncome) tones.income else MaterialTheme.colorScheme.onSurface)
            if (!r.isIncome && r.lastAmount > r.previousAmount * 1.05) Label("was ${money(r.previousAmount)}")
        }
    }
}
