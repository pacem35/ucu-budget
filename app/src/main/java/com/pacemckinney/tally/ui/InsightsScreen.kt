package com.pacemckinney.tally.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.pacemckinney.tally.engine.Kind
import com.pacemckinney.tally.engine.MonthFlow
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
fun InsightsScreen(state: UiState, onMonth: (java.time.YearMonth) -> Unit) {
    val s = state.snapshot ?: return
    val tones = LocalTones.current
    val bills = s.recurring.filter { it.kind == Kind.SPEND }
    val loanStreams = s.recurring.filter { it.kind == Kind.LOAN_PAYMENT }
    val saveStreams = s.recurring.filter { it.kind == Kind.SAVINGS }
    val income = s.recurring.filter { it.isIncome }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Section(title = "What Tally noticed") {
                if (s.insights.isEmpty()) Text("Nothing yet. Insights appear once there's a few weeks of history.", color = tones.muted)
                s.insights.forEach { InsightRow(it) }
            }
        }
        if (s.history.any { it.income > 0 || it.spending > 0 }) item {
            Section(title = "Last 6 months") {
                HistoryChart(s.history)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendSwatch(tones.income, "Income")
                    LegendSwatch(MaterialTheme.colorScheme.secondary, "Spent")
                    LegendSwatch(tones.loan, "Loans")
                    LegendSwatch(tones.savings, "Saved")
                }
                Spacer(Modifier.height(12.dp))
                HistoryTable(s.history, onMonth)
                Spacer(Modifier.height(6.dp))
                Label("Tap a month to see every transaction behind it.")
            }
        }
        if (income.isNotEmpty()) item {
            Section(title = "Regular income") {
                income.forEach { StreamRow(it) }
            }
        }
        if (loanStreams.isNotEmpty()) item {
            Section(title = "Loan & card payments · ${money(loanStreams.sumOf { it.monthlyAmount })}/mo") {
                loanStreams.forEach { StreamRow(it) }
            }
        }
        if (saveStreams.isNotEmpty()) item {
            Section(title = "Automatic savings · ${money(saveStreams.sumOf { it.monthlyAmount })}/mo") {
                saveStreams.forEach { StreamRow(it) }
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
                    "Transfers to savings count as saved, payments to a loan as loan payments, and paying your tracked credit card " +
                    "isn't counted twice. Accounts you mark \"not mine\" are ignored entirely. " +
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
        KindBadge(r.kind, r.category, 32)
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

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalTones.current.muted)
    }
}

/** Per month: an income bar beside a stacked bar of spending, loan payments and savings. */
@Composable
private fun HistoryChart(history: List<MonthFlow>) {
    val tones = LocalTones.current
    val spend = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val maxV = history.maxOf { maxOf(it.income, it.spending + it.loanPayments + maxOf(it.saved, 0.0)) }.coerceAtLeast(1.0)
    Column {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            for (g in 1..3) {
                val y = size.height * g / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            val slot = size.width / history.size
            val barW = slot * 0.3f
            history.forEachIndexed { i, m ->
                val x0 = i * slot + slot * 0.15f
                fun h(v: Double) = (v / maxV * size.height).toFloat()
                // Income
                val ih = h(m.income)
                drawRoundRect(tones.income, Offset(x0, size.height - ih), Size(barW, ih), CornerRadius(4f))
                // Outflows, stacked
                var top = size.height
                for ((v, c) in listOf(m.spending to spend, m.loanPayments to tones.loan, maxOf(m.saved, 0.0) to tones.savings)) {
                    val hh = h(v)
                    if (hh <= 0) continue
                    top -= hh
                    drawRect(c, Offset(x0 + barW + 4f, top), Size(barW, hh))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            history.forEach { m ->
                Text(m.month.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = LocalTones.current.muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun HistoryTable(history: List<MonthFlow>, onMonth: (java.time.YearMonth) -> Unit) {
    val tones = LocalTones.current
    Row(Modifier.fillMaxWidth()) {
        Text("", Modifier.weight(0.8f))
        listOf("In", "Spent", "Loans", "Saved", "Left").forEach {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = tones.muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
    history.reversed().forEach { m ->
        Row(Modifier.fillMaxWidth().clickable { onMonth(m.month) }.padding(vertical = 6.dp)) {
            Text(m.month.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US), Modifier.weight(0.8f),
                style = MaterialTheme.typography.bodySmall, color = tones.muted)
            listOf(
                m.income to tones.income, m.spending to MaterialTheme.colorScheme.onSurface,
                m.loanPayments to tones.loan, m.saved to tones.savings,
                m.leftOver to if (m.leftOver < 0) tones.alert else MaterialTheme.colorScheme.onSurface,
            ).forEach { (v, c) ->
                Text(short(v), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.tabular(), color = c,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** Compact money for tight table cells: $1.2k, $85. */
private fun short(v: Double): String {
    val a = kotlin.math.abs(v)
    val s = if (a >= 1000) String.format(Locale.US, "$%.1fk", a / 1000) else String.format(Locale.US, "$%.0f", a)
    return if (v < 0) "-$s" else s
}
