package com.pacemckinney.tally.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pacemckinney.tally.engine.Categories
import com.pacemckinney.tally.engine.Kind
import com.pacemckinney.tally.engine.Txn
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Every transaction behind one month's numbers, grouped the same way the totals are, so you can
 * check them against your statement and fix anything mislabelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthDetailScreen(
    month: YearMonth,
    state: UiState,
    onBack: () -> Unit,
    onSetCategory: (Txn, String?, Boolean) -> Unit,
) {
    BackHandler(onBack = onBack)
    val tones = LocalTones.current
    var editing by remember { mutableStateOf<Txn?>(null) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggle(k: String) { expanded = if (k in expanded) expanded - k else expanded + k }

    val inMonth = remember(state.txns, month) { state.txns.filter { YearMonth.from(it.date) == month } }
    fun of(vararg kinds: Kind) = inMonth.filter { state.kinds[it.id] in kinds }
    val income = of(Kind.INCOME).sortedBy { it.amount }
    val spend = of(Kind.SPEND, Kind.REFUND)
    val loans = of(Kind.LOAN_PAYMENT).sortedByDescending { it.amount }
    val saved = of(Kind.SAVINGS).sortedByDescending { kotlin.math.abs(it.amount) }
    val ignored = of(Kind.INTERNAL, Kind.EXCLUDED).sortedByDescending { kotlin.math.abs(it.amount) }
    val byCat = spend.groupBy { it.effectiveCategory }.map { (c, l) -> Triple(c, l.sumOf { it.amount }, l.sortedByDescending { it.amount }) }
        .sortedByDescending { it.second }

    val title = month.month.getDisplayName(TextStyle.FULL, Locale.US) + " " + month.year

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "Everything behind this month's totals. Tap a transaction to change what it counts as.",
                    style = MaterialTheme.typography.bodyMedium, color = tones.muted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            group("income", "Income", -income.sumOf { it.amount }, tones.income, income.size, "income" in expanded, ::toggle) {
                items(income, key = { it.id }) { t -> row(t, state) { editing = t } }
            }

            item(key = "h-spend") {
                GroupHeader("Spending", spend.sumOf { it.amount }, MaterialTheme.colorScheme.secondary, spend.size, null, null)
            }
            for ((cat, total, list) in byCat) {
                val key = "cat-$cat"
                item(key = key) {
                    Row(
                        Modifier.fillMaxWidth().clickable { toggle(key) }.padding(start = 32.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(categoryIcon(cat), null, Modifier.size(18.dp), tint = tones.muted)
                        Spacer(Modifier.width(10.dp))
                        Text(Categories.label(cat), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Label("${list.size}  ")
                        Text(money(total), style = MaterialTheme.typography.bodyLarge.tabular())
                        Icon(if (key in expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = tones.muted)
                    }
                }
                if (key in expanded) items(list, key = { it.id }) { t -> row(t, state) { editing = t } }
            }

            group("loans", "Loans & debt", loans.sumOf { it.amount }, tones.loan, loans.size, "loans" in expanded, ::toggle) {
                items(loans, key = { it.id }) { t -> row(t, state) { editing = t } }
            }
            group("saved", "Saved (net)", saved.sumOf { it.amount }, tones.savings, saved.size, "saved" in expanded, ::toggle) {
                items(saved, key = { it.id }) { t -> row(t, state) { editing = t } }
            }
            group("ignored", "Not counted", null, tones.muted, ignored.size, "ignored" in expanded, ::toggle,
                note = "Moves between your own accounts, card payoffs, loan-side activity and anything you excluded.") {
                items(ignored, key = { it.id }) { t -> row(t, state) { editing = t } }
            }
        }
    }

    editing?.let { t ->
        CategoryPickerDialog(t, onDismiss = { editing = null }) { cat, all ->
            onSetCategory(t, cat, all)
            editing = null
        }
    }
}

@Composable
private fun row(t: Txn, state: UiState, onClick: () -> Unit) {
    Column {
        TxnRow(t, state.kinds[t.id], state.notes[t.id], state.accountLabels[t.accountId], onClick)
        Text(t.date.toString(), style = MaterialTheme.typography.labelSmall, color = LocalTones.current.muted,
            modifier = Modifier.padding(start = 64.dp, bottom = 4.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.group(
    key: String,
    title: String,
    total: Double?,
    color: Color,
    count: Int,
    open: Boolean,
    toggle: (String) -> Unit,
    note: String? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    if (count == 0) return
    item(key = "h-$key") { GroupHeader(title, total, color, count, open) { toggle(key) } }
    if (open) {
        if (note != null) item(key = "n-$key") {
            Text(note, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        content()
    }
}

@Composable
private fun GroupHeader(title: String, total: Double?, color: Color, count: Int, open: Boolean?, onClick: (() -> Unit)?) {
    Column {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Label("$count  ")
            if (total != null) Text(money(total), style = MaterialTheme.typography.titleMedium.tabular(), fontWeight = FontWeight.SemiBold)
            if (open != null) Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = LocalTones.current.muted)
        }
    }
}
