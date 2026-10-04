package com.pacemckinney.tally.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pacemckinney.tally.engine.Categories
import kotlin.math.ceil

@Composable
fun BudgetsScreen(state: UiState, onSetBudget: (String, Double?) -> Unit, onSetSavingsGoal: (Double?) -> Unit) {
    val s = state.snapshot ?: return
    val tones = LocalTones.current
    var editing by remember { mutableStateOf<String?>(null) }
    var editingGoal by remember { mutableStateOf(false) }
    val spent = s.byCategory.associate { it.category to it.amount }
    val budgeted = s.budgets.map { it.budget.category }.toSet()
    val others = Categories.budgetable.filter { it !in budgeted }
        .sortedByDescending { (spent[it] ?: 0.0) + (state.avgByCategory[it] ?: 0.0) }
    val monthFrac = s.today.dayOfMonth.toFloat() / s.month.lengthOfMonth()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Savings goal: how much to move into savings each month.
        item {
            val goal = state.savingsGoal
            val saved = s.savedMtd
            Section(title = "Savings goal", action = { TextButton(onClick = { editingGoal = true }) { Text(if (goal > 0) "Edit" else "Set") } }) {
                if (goal <= 0) {
                    Text(
                        "Set a monthly amount to move into savings. Tally tracks transfers into your savings accounts against it." +
                            (if (saved > 0) " You've saved ${money(saved)} so far this month." else ""),
                        style = MaterialTheme.typography.bodyMedium, color = tones.muted,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(money(maxOf(saved, 0.0)), style = MaterialTheme.typography.headlineMedium.tabular(), fontWeight = FontWeight.SemiBold,
                            color = tones.savings)
                        Text("  of ${money(goal)}", style = MaterialTheme.typography.titleMedium, color = tones.muted,
                            modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Bar((saved / goal).toFloat(), if (saved >= goal) tones.good else tones.savings)
                    Spacer(Modifier.height(6.dp))
                    Label(when {
                        saved < 0 -> "${money(-saved)} pulled out of savings this month"
                        saved >= goal -> "Goal reached · savings balance ${money(s.balances.savings)}"
                        else -> "${money(goal - saved)} to go · savings balance ${money(s.balances.savings)}"
                    })
                }
            }
        }
        item {
            val total = s.budgets.sumOf { it.budget.monthlyLimit }
            val used = s.budgets.sumOf { it.spent }
            Section(title = "This month's budgets") {
                if (s.budgets.isEmpty()) {
                    Text("No budgets yet. Tap a category below to set a monthly limit. Tally shows your 3-month average to help you pick a number.",
                        style = MaterialTheme.typography.bodyMedium, color = tones.muted)
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(money(used), style = MaterialTheme.typography.headlineMedium.tabular(), fontWeight = FontWeight.SemiBold)
                        Text("  of ${money(total)}", style = MaterialTheme.typography.titleMedium, color = tones.muted,
                            modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Bar((used / total).toFloat(), if (used > total) tones.alert else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Label("${(monthFrac * 100).toInt()}% of the month has gone by")
                }
            }
        }

        if (s.budgets.isNotEmpty()) item {
            Section {
                s.budgets.forEachIndexed { i, b ->
                    if (i > 0) Spacer(Modifier.height(14.dp))
                    Column(Modifier.fillMaxWidth().clickable { editing = b.budget.category }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(b.budget.category, size = 30)
                            Spacer(Modifier.width(10.dp))
                            Text(Categories.label(b.budget.category), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("${money(b.spent)} / ${money(b.budget.monthlyLimit)}", style = MaterialTheme.typography.bodyMedium.tabular())
                        }
                        Spacer(Modifier.height(6.dp))
                        val color = when {
                            b.fraction >= 1 -> tones.alert
                            b.fraction >= 0.8 || b.projected > b.budget.monthlyLimit -> tones.warn
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Bar(b.fraction.toFloat(), color)
                        Spacer(Modifier.height(4.dp))
                        val note = when {
                            b.remaining < 0 -> "${money(-b.remaining)} over"
                            b.projected > b.budget.monthlyLimit && s.today.dayOfMonth >= 5 -> "${money(b.remaining)} left · on pace for ${money(b.projected)}"
                            else -> "${money(b.remaining)} left"
                        }
                        Label(note)
                    }
                }
            }
        }

        item {
            Section(title = "Add a budget") {
                others.forEachIndexed { i, c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = c }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryBadge(c, MaterialTheme.colorScheme.secondary, size = 30)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Categories.label(c), style = MaterialTheme.typography.bodyLarge)
                            val avg = state.avgByCategory[c]
                            Label(listOfNotNull(
                                spent[c]?.let { "${money(it)} this month" },
                                avg?.takeIf { it > 1 }?.let { "avg ${money(it)}/mo" },
                            ).joinToString(" · ").ifEmpty { "No spending yet" })
                        }
                        Text("Set", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (editingGoal) {
        var text by remember { mutableStateOf(state.savingsGoal.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "") }
        val avgSaved = s.history.dropLast(1).filter { it.income > 0 }.let { h -> if (h.isEmpty()) null else h.sumOf { it.saved } / h.size }
        AlertDialog(
            onDismissRequest = { editingGoal = false },
            title = { Text("Monthly savings goal") },
            text = {
                Column {
                    if (avgSaved != null && avgSaved > 1) Text("You've averaged ${money(avgSaved)} a month into savings recently.",
                        style = MaterialTheme.typography.bodyMedium, color = tones.muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' } },
                        prefix = { Text("$") }, suffix = { Text("/ month") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onSetSavingsGoal(text.toDoubleOrNull()); editingGoal = false }) { Text("Save") } },
            dismissButton = {
                Row {
                    if (state.savingsGoal > 0) TextButton(onClick = { onSetSavingsGoal(null); editingGoal = false }) { Text("Remove") }
                    TextButton(onClick = { editingGoal = false }) { Text("Cancel") }
                }
            },
        )
    }

    editing?.let { cat ->
        val current = s.budgets.firstOrNull { it.budget.category == cat }?.budget?.monthlyLimit
        val avg = state.avgByCategory[cat]
        var text by remember(cat) {
            mutableStateOf(current?.let { "%.0f".format(it) } ?: avg?.takeIf { it > 1 }?.let { (ceil(it / 10) * 10).toInt().toString() } ?: "")
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${Categories.label(cat)} budget") },
            text = {
                Column {
                    if (avg != null && avg > 1) Text("You've averaged ${money(avg)} a month over the last 3 months.",
                        style = MaterialTheme.typography.bodyMedium, color = tones.muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' } },
                        prefix = { Text("$") }, suffix = { Text("/ month") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSetBudget(cat, text.toDoubleOrNull()); editing = null }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (current != null) TextButton(onClick = { onSetBudget(cat, null); editing = null }) { Text("Remove") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
