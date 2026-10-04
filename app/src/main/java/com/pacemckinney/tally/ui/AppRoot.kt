package com.pacemckinney.tally.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    ACTIVITY("Activity", Icons.Outlined.ReceiptLong),
    BUDGETS("Budgets", Icons.Outlined.PieChart),
    INSIGHTS("Insights", Icons.Outlined.Insights),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** Things only the Activity can do (Plaid Link needs an Activity result launcher). */
interface Host {
    fun connectBank(updateItemId: String? = null)
    fun rescheduleSync(minutes: Int)
    val linking: Boolean
}

@Composable
fun AppRoot(vm: MainViewModel, host: Host) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var showAccounts by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snack.showSnackbar(it); vm.toast(null) }
    }

    if (!state.loaded) return Box(Modifier.fillMaxSize())

    if (!state.hasKeys) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
            Box(Modifier.padding(pad)) { SetupScreen(vm::saveKeys) }
        }
        return
    }

    // Keys saved but nothing connected yet (and not peeking at settings).
    if (state.items.isEmpty() && tab != Tab.SETTINGS) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
            Box(Modifier.padding(pad)) {
                ConnectScreen(vm.repo.store.environment, host.linking, onConnect = { host.connectBank() }, onSettings = { tab = Tab.SETTINGS })
            }
        }
        return
    }

    if (showAccounts) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
            Box(Modifier.padding(pad)) {
                AccountsScreen(
                    accounts = state.accounts,
                    txnCounts = state.txnCounts,
                    onChange = vm::setAccount,
                    onDone = { vm.markAccountsReviewed(); showAccounts = false },
                )
            }
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> HomeScreen(
                    state, onRefresh = { vm.refresh(live = true) }, onReauth = { host.connectBank(it) },
                    onSeeInsights = { tab = Tab.INSIGHTS }, onSeeBudgets = { tab = Tab.BUDGETS },
                    onManageAccounts = { showAccounts = true },
                )
                Tab.ACTIVITY -> ActivityScreen(state, onRefresh = { vm.refresh(live = false) }, onSetCategory = vm::setCategory)
                Tab.BUDGETS -> BudgetsScreen(state, vm::setBudget, vm::setSavingsGoal)
                Tab.INSIGHTS -> InsightsScreen(state)
                Tab.SETTINGS -> SettingsScreen(
                    state, vm.repo.store, vm.repo.prefs,
                    onSaveKeys = { id, s, env -> vm.saveKeys(id, s.ifBlank { vm.repo.store.secret ?: "" }, env) },
                    onAddBank = { host.connectBank() },
                    onManageAccounts = { showAccounts = true },
                    onReauth = { host.connectBank(it) },
                    onDisconnect = vm::disconnect,
                    onSyncMinutes = { vm.repo.prefs.syncMinutes = it; host.rescheduleSync(it) },
                    onChanged = vm::settingsChanged,
                    onReset = { vm.resetEverything(); tab = Tab.HOME },
                )
            }
        }
    }
}
