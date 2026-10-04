package com.pacemckinney.tally.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pacemckinney.tally.engine.Categories
import com.pacemckinney.tally.engine.Kind
import com.pacemckinney.tally.engine.Txn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Filter(val label: String) {
    ALL("All"), SPENT("Spending"), IN("Income"), SAVINGS("Savings"), LOANS("Loans & debt"), MOVES("Transfers"), PENDING("Pending")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(state: UiState, onRefresh: () -> Unit, onSetCategory: (Txn, String?, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US) }
    val today = LocalDate.now()

    val shown = remember(state.txns, query, filter) {
        val q = query.trim().lowercase()
        state.txns.filter { t ->
            val k = state.kinds[t.id]
            val passFilter = when (filter) {
                Filter.ALL -> true
                Filter.SPENT -> k == Kind.SPEND || k == Kind.REFUND
                Filter.IN -> k == Kind.INCOME
                Filter.SAVINGS -> k == Kind.SAVINGS
                Filter.LOANS -> k == Kind.LOAN_PAYMENT
                Filter.MOVES -> k == Kind.INTERNAL || k == Kind.EXCLUDED
                Filter.PENDING -> t.pending
            }
            passFilter && (q.isEmpty() || t.displayName.lowercase().contains(q) || t.name.lowercase().contains(q) ||
                Categories.label(t.effectiveCategory).lowercase().contains(q) ||
                String.format(Locale.US, "%.2f", kotlin.math.abs(t.amount)).contains(q))
        }.take(1500)
    }
    val grouped = remember(shown) { shown.groupBy { it.date } }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            placeholder = { Text("Search merchant, category or amount") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
        }
        PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (shown.isEmpty()) item {
                    Text(if (state.txns.isEmpty()) "No transactions yet." else "Nothing matches.",
                        Modifier.padding(24.dp), color = LocalTones.current.muted)
                }
                grouped.forEach { (date, list) ->
                    item(key = "h$date") {
                        val net = list.filter { state.kinds[it.id] == Kind.SPEND || state.kinds[it.id] == Kind.REFUND }.sumOf { it.amount }
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
                        ) {
                            Label(
                                when (date) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> date.format(fmt) },
                                Modifier.weight(1f),
                            )
                            if (net > 0) Label("${money(net)} spent")
                        }
                    }
                    items(list, key = { it.id }) { t -> TxnRow(t, state.kinds[t.id], state.notes[t.id], state.accountLabels[t.accountId]) { editing = t } }
                }
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
