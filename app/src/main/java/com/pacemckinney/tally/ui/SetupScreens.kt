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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun KeysForm(
    initialClientId: String,
    initialEnv: String,
    hasSecret: Boolean,
    onSave: (String, String, String) -> Unit,
) {
    var clientId by remember { mutableStateOf(initialClientId) }
    var secret by remember { mutableStateOf("") }
    var env by remember { mutableStateOf(initialEnv) }
    var show by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("production" to "Real bank", "sandbox" to "Sandbox (test)").forEachIndexed { i, (v, l) ->
                SegmentedButton(selected = env == v, onClick = { env = v }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
            }
        }
        OutlinedTextField(clientId, { clientId = it.trim() }, label = { Text("client_id") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        OutlinedTextField(
            secret, { secret = it.trim() },
            label = { Text(if (env == "sandbox") "Sandbox secret" else "Production secret") },
            placeholder = { if (hasSecret) Text("Saved — leave blank to keep") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Button(
            onClick = { onSave(clientId, secret, env) },
            enabled = clientId.isNotBlank() && (secret.isNotBlank() || hasSecret),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save keys") }
    }
}

@Composable
fun SetupScreen(onSave: (String, String, String) -> Unit) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Tally", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        Text("Your UCU spending, income and insights, connected through Plaid.",
            style = MaterialTheme.typography.titleMedium)

        Section(title = "One-time setup (about 5 minutes)") {
            Step(1, "Create a free Plaid account at dashboard.plaid.com. When it asks what you're building, pick \"Personal use\". That puts you on Plaid's free Trial plan.")
            Step(2, "In the Plaid dashboard, open Developers → Keys and copy your client_id and Production secret.")
            Step(3, "Open Developers → API → Allowed Android package names and add:\ncom.pacemckinney.tally")
            Step(4, "Paste the keys below. Use \"Sandbox\" with the sandbox secret if you want to try it with a fake bank first.")
            TextButton(onClick = { uri.openUri("https://dashboard.plaid.com/signup") }) { Text("Open Plaid sign-up") }
        }

        Section {
            KeysForm("", "production", hasSecret = false, onSave = onSave)
        }

        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp).padding(top = 2.dp), tint = LocalTones.current.muted)
            Spacer(Modifier.width(8.dp))
            Text("Keys and bank tokens are encrypted with this phone's hardware keystore and never leave it, except to talk to Plaid. " +
                "Tally never sees your UCU username or password — you type those into Plaid's own screen.",
                style = MaterialTheme.typography.bodySmall, color = LocalTones.current.muted)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Text("$n", Modifier.width(22.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ConnectScreen(env: String, busy: Boolean, onConnect: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.AccountBalance, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Connect United Credit Union", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (env == "sandbox") "Sandbox mode: pick any test bank and sign in with user_good / pass_good."
            else "In Plaid's screen, search for \"United Credit Union (MO)\" and sign in with your UCU online banking login.",
            style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Opening Plaid…" else "Connect")
        }
        TextButton(onClick = onSettings) { Text("Settings") }
    }
}
