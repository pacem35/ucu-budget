package com.pacemckinney.tally.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pacemckinney.tally.engine.PlannedPurchase
import com.pacemckinney.tally.engine.Report
import com.pacemckinney.tally.report.PdfExporter
import com.pacemckinney.tally.report.ReportOptions
import com.pacemckinney.tally.report.ReportRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Build a one-page-plus financial summary PDF for a lender, dealer or landlord. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tones = LocalTones.current
    val prefs = vm.repo.prefs

    var name by rememberSaveable { mutableStateOf(prefs.reportName) }
    var months by rememberSaveable { mutableStateOf(3) }
    var withTxns by rememberSaveable { mutableStateOf(false) }
    var withPurchase by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var down by rememberSaveable { mutableStateOf("") }
    var apr by rememberSaveable { mutableStateOf("") }
    var term by rememberSaveable { mutableStateOf(60) }
    var extra by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf<Report?>(null) }

    fun num(s: String) = s.replace(",", "").toDoubleOrNull() ?: 0.0
    val purchase = if (withPurchase && num(price) > 0) PlannedPurchase(
        label.trim(), num(price), num(down), num(apr), term, num(extra),
    ) else null

    // Numbers for the live preview (rebuilt only when the period changes).
    LaunchedEffect(months) { base = vm.buildReport(months, null) }

    fun options() = ReportOptions(name = name.trim(), institution = vm.institutionName(), includeTransactions = withTxns)

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val r = vm.buildReport(months, purchase)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { PdfExporter.write(r, options(), it) }
                }
                vm.toast("Saved")
            } catch (e: Exception) {
                vm.toast("Couldn't save the PDF: ${e.message}")
            } finally { busy = false }
        }
    }

    fun share() = scope.launch {
        busy = true
        prefs.reportName = name.trim()
        try {
            val r = vm.buildReport(months, purchase)
            val file = withContext(Dispatchers.IO) { PdfExporter.writeToCache(ctx, r, options()) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Financial summary")
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share financial summary"))
        } catch (e: Exception) {
            vm.toast("Couldn't create the PDF: ${e.message}")
        } finally { busy = false }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Export PDF") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A financial summary to hand a lender, dealer or landlord: average take-home pay, monthly bills and debt payments, " +
                    "debt-to-income, balances and month-by-month totals. Add a purchase to show how its payment fits.",
                style = MaterialTheme.typography.bodyMedium, color = tones.muted,
            )

            Section(title = "Report") {
                OutlinedTextField(name, { name = it }, label = { Text("Name on report (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Label("Period (full months)")
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 6, 12).forEach { mo -> FilterChip(selected = months == mo, onClick = { months = mo }, label = { Text("$mo months") }) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Include transaction list", style = MaterialTheme.typography.bodyLarge)
                        Label("Adds every transaction in the period as an appendix")
                    }
                    Switch(withTxns, { withTxns = it })
                }
            }

            Section(title = "Planned purchase") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Price out a purchase", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(withPurchase, { withPurchase = it })
                }
                if (withPurchase) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(label, { label = it }, label = { Text("What is it? (e.g. 2019 Tacoma)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        MoneyInput("Price", price, Modifier.weight(1f)) { price = it }
                        MoneyInput("Down payment", down, Modifier.weight(1f)) { down = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(apr, { v -> apr = v.filter { it.isDigit() || it == '.' } }, label = { Text("APR") },
                            suffix = { Text("%") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        MoneyInput("Other monthly costs", extra, Modifier.weight(1f)) { extra = it }
                    }
                    Spacer(Modifier.height(10.dp))
                    Label("Term")
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Cash", 12 to "12 mo", 24 to "24 mo", 36 to "36 mo", 48 to "48 mo", 60 to "60 mo", 72 to "72 mo", 84 to "84 mo")
                            .forEach { (v, l) -> FilterChip(selected = term == v, onClick = { term = v }, label = { Text(l) }) }
                    }
                    Label("Other monthly costs: insurance, fuel, upkeep, etc. that come with it.", Modifier.padding(top = 6.dp))
                }
            }

            // Live preview of the headline numbers.
            base?.let { r ->
                Section(title = "Preview") {
                    PreviewLine("Avg. monthly take-home", money(r.avgIncome))
                    PreviewLine("Fixed obligations / month", money(r.fixedMonthly))
                    PreviewLine("Debt-to-income", r.dti?.let { "${(it * 100).toInt()}%" } ?: "—")
                    PreviewLine("Avg. left over / month", money(r.avgLeftOver))
                    if (purchase != null) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        PreviewLine("Monthly payment", money(purchase.monthlyPayment), bold = true)
                        if (purchase.termMonths > 0) PreviewLine("Total interest", money(purchase.totalInterest))
                        val dtiAfter = if (r.avgIncome > 0) (r.housingMonthly + r.debtMonthly + purchase.monthlyPayment) / r.avgIncome else null
                        PreviewLine("Debt-to-income after", dtiAfter?.let { "${(it * 100).toInt()}%" } ?: "—",
                            color = when { dtiAfter == null -> null; dtiAfter > 0.43 -> tones.alert; dtiAfter > 0.36 -> tones.warn; else -> tones.good })
                        val left = r.avgLeftOver - purchase.monthlyCost
                        PreviewLine("Left over after", money(left), color = if (left < 0) tones.alert else null)
                    }
                    if (r.months.isEmpty()) Label("Not enough history yet: the report needs at least one complete month.")
                    else Label("Based on ${r.months.size} complete month${if (r.months.size == 1) "" else "s"}.")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { prefs.reportName = name.trim(); saveLauncher.launch(ReportRenderer.fileName(LocalDate.now())) },
                    enabled = !busy, modifier = Modifier.weight(1f),
                ) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(6.dp)); Text("Save") }
                Button(onClick = { share() }, enabled = !busy, modifier = Modifier.weight(1.4f)) {
                    Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "Creating…" else "Share PDF")
                }
            }
        }
    }
}

@Composable
private fun MoneyInput(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) }, prefix = { Text("$") }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@Composable
private fun PreviewLine(label: String, value: String, bold: Boolean = false, color: androidx.compose.ui.graphics.Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
            color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}
