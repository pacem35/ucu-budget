package com.pacemckinney.tally.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pacemckinney.tally.engine.Account
import com.pacemckinney.tally.engine.AccountRole

/**
 * Choose which accounts Plaid connected are actually yours, and what each one is for.
 * Untracked accounts disappear from every total, chart, alert and the Activity list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    accounts: List<Account>,
    txnCounts: Map<String, Int>,
    onChange: (Account, Boolean, AccountRole, String?) -> Unit,
    onDone: () -> Unit,
) {
    BackHandler(onBack = onDone)
    var renaming by remember { mutableStateOf<Account?>(null) }
    val order = listOf(AccountRole.SPENDING, AccountRole.SAVINGS, AccountRole.CREDIT, AccountRole.LOAN)
    val sorted = accounts.sortedWith(compareBy({ !it.included }, { order.indexOf(it.role) }, { it.cleanName }))

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Your accounts") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Plaid shares every account your UCU login can see, including joint accounts and ones you're only " +
                        "listed on. Turn off the ones that aren't yours. Tally will ignore their balances and transactions; " +
                        "money you send to them counts as spending.",
                    style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(sorted, key = { it.id }) { a ->
                AccountCard(a, txnCounts[a.id] ?: 0, onChange, onRename = { renaming = a })
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Done") }
        }
    }

    renaming?.let { a ->
        var text by remember(a.id) { mutableStateOf(a.nickname ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename account") },
            text = {
                Column {
                    Text(a.name, style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(a.cleanName) })
                }
            },
            confirmButton = { TextButton(onClick = { onChange(a, a.included, a.role, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountCard(
    a: Account,
    txnCount: Int,
    onChange: (Account, Boolean, AccountRole, String?) -> Unit,
    onRename: () -> Unit,
) {
    val tones = LocalTones.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).alpha(if (a.included) 1f else 0.6f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onRename)) {
                        Text(a.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Edit, "Rename", Modifier.padding(top = 2.dp).width(16.dp), tint = tones.muted)
                    }
                    Label(listOfNotNull(
                        a.mask?.let { "••$it" },
                        a.subtype?.replaceFirstChar { it.uppercase() },
                        balanceText(a),
                        if (txnCount > 0) "$txnCount transactions" else null,
                    ).joinToString(" · "))
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = a.included, onCheckedChange = { onChange(a, it, a.role, a.nickname) })
                    Label(if (a.included) "Mine" else "Not mine")
                }
            }
            if (a.included) {
                Spacer(Modifier.height(10.dp))
                Label("Counts as")
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountRole.entries.forEach { r ->
                        FilterChip(
                            selected = a.role == r,
                            onClick = { onChange(a, true, r, a.nickname) },
                            label = { Text(r.label) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Label(roleHelp(a.role))
            }
        }
    }
}

private fun balanceText(a: Account): String = when (a.role) {
    AccountRole.CREDIT, AccountRole.LOAN -> "${money(a.owed)} owed"
    else -> money(a.current ?: a.available ?: 0.0)
}

private fun roleHelp(r: AccountRole) = when (r) {
    AccountRole.SPENDING -> "Its balance is your spending money; purchases count as spending."
    AccountRole.SAVINGS -> "Money moved in counts as saved, money moved out as pulled from savings."
    AccountRole.CREDIT -> "Purchases count as spending; paying it off from checking isn't counted twice."
    AccountRole.LOAN -> "Payments from checking count as loan payments; shows what's left to pay."
}
