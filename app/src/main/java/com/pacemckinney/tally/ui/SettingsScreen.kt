package com.pacemckinney.tally.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pacemckinney.tally.data.Prefs
import com.pacemckinney.tally.data.SecureStore

@Composable
fun SettingsScreen(
    state: UiState,
    store: SecureStore,
    prefs: Prefs,
    onSaveKeys: (String, String, String) -> Unit,
    onAddBank: () -> Unit,
    onManageAccounts: () -> Unit,
    onExport: () -> Unit,
    onReauth: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onSyncMinutes: (Int) -> Unit,
    onChanged: () -> Unit,
    onReset: () -> Unit,
) {
    val tones = LocalTones.current
    var confirmDisconnect by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var editKeys by remember { mutableStateOf(false) }
    // Local mirrors so toggles redraw immediately; Prefs writes are synchronous.
    var minutes by remember { mutableIntStateOf(prefs.syncMinutes) }
    var everyTxn by remember { mutableStateOf(prefs.notifyEveryTxn) }
    var income by remember { mutableStateOf(prefs.notifyIncome) }
    var alerts by remember { mutableStateOf(prefs.notifyAlerts) }
    var lock by remember { mutableStateOf(prefs.biometricLock) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(title = "Connected banks") {
            if (state.items.isEmpty()) Text("None yet.", color = tones.muted)
            state.items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(item.institution, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val accts = state.accounts.filter { it.itemId == item.itemId }
                val tracked = accts.count { it.included }
                Label(if (accts.isEmpty()) "Waiting for first sync" else "$tracked of ${accts.size} accounts tracked")
                when {
                    item.needsReauth -> Text("Sign-in expired — tap Re-connect.", color = tones.alert, style = MaterialTheme.typography.bodySmall)
                    item.lastError != null -> Text(item.lastError, color = tones.warn, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = { onReauth(item.itemId) }) { Text("Re-connect") }
                    TextButton(onClick = { confirmDisconnect = item.itemId }) { Text("Disconnect", color = tones.alert) }
                }
            }
            if (state.accounts.isNotEmpty()) OutlinedButton(onClick = onManageAccounts, modifier = Modifier.fillMaxWidth()) {
                Text("Choose accounts & types")
            }
            OutlinedButton(onClick = onAddBank, modifier = Modifier.fillMaxWidth()) { Text("Connect another bank") }
        }

        Section(title = "Reports") {
            Text("Financial summary PDF for loans, dealers and landlords: income, obligations, debt-to-income and an optional purchase estimate.",
                style = MaterialTheme.typography.bodyMedium, color = tones.muted)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("Export PDF") }
        }

        Section(title = "Background updates") {
            Text("Check UCU for new activity every", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 hr", 180 to "3 hr").forEach { (m, l) ->
                    FilterChip(selected = minutes == m, onClick = { minutes = m; onSyncMinutes(m) }, label = { Text(l) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Label("UCU typically shares new transactions with Plaid a few times a day; pending card swipes can show up sooner. Pull down on Home for live balances any time.")
        }

        Section(title = "Notifications") {
            Toggle("Every new charge", "Off = only charges over the amount below", everyTxn) { everyTxn = it; prefs.notifyEveryTxn = it }
            if (!everyTxn) MoneyField("Notify for charges over", prefs.notifyThreshold) { prefs.notifyThreshold = it }
            Toggle("Deposits", "Paychecks and money coming in", income) { income = it; prefs.notifyIncome = it }
            Toggle("Alerts", "Budgets at 80%/over, low balance, double charges, fees", alerts) { alerts = it; prefs.notifyAlerts = it }
            MoneyField("Low balance warning below", prefs.lowBalance) { prefs.lowBalance = it; onChanged() }
            MoneyField("Flag single charges over", prefs.largeThreshold) { prefs.largeThreshold = it; onChanged() }
        }

        Section(title = "Security") {
            Toggle("Lock with fingerprint / PIN", "Asked when you open Tally", lock) { lock = it; prefs.biometricLock = it }
        }

        Section(title = "Plaid keys") {
            Label("Environment: ${store.environment} · client_id: ${store.clientId?.take(6) ?: "—"}…")
            if (editKeys) {
                Spacer(Modifier.height(8.dp))
                KeysForm(store.clientId ?: "", store.environment, hasSecret = store.secret != null) { id, s, env ->
                    onSaveKeys(id, s, env); editKeys = false
                }
            } else TextButton(onClick = { editKeys = true }) { Text("Change keys") }
        }

        Section(title = "Data") {
            Label("${state.txns.size} transactions stored on this phone.")
            TextButton(onClick = { confirmReset = true }) { Text("Erase everything & start over", color = tones.alert) }
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
        Text("Tally $version", style = MaterialTheme.typography.labelSmall, color = tones.muted,
            modifier = Modifier.align(Alignment.CenterHorizontally))
    }

    confirmDisconnect?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDisconnect = null },
            title = { Text("Disconnect this bank?") },
            text = { Text("Tally removes its Plaid connection and deletes that bank's transactions from this phone.") },
            confirmButton = { TextButton(onClick = { onDisconnect(id); confirmDisconnect = null }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = null }) { Text("Cancel") } },
        )
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Erase everything?") },
        text = { Text("Removes your Plaid keys, bank connections, transactions, budgets and edits from this phone.") },
        confirmButton = { TextButton(onClick = { onReset(); confirmReset = false }) { Text("Erase") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Label(sub)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MoneyField(label: String, value: Double, onSave: (Double) -> Unit) {
    var text by remember { mutableStateOf("%.0f".format(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() || it == '.' }
            text.toDoubleOrNull()?.let(onSave)
        },
        label = { Text(label) }, prefix = { Text("$") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
