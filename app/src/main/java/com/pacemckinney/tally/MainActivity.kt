package com.pacemckinney.tally

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.plaid.link.OnLoadCallback
import com.plaid.link.OpenPlaidLink
import com.plaid.link.Plaid
import com.plaid.link.PlaidLinkSession
import com.plaid.link.PlaidSession
import com.plaid.link.configuration.linkTokenConfiguration
import com.plaid.link.result.LinkExit
import com.plaid.link.result.LinkResult
import com.plaid.link.result.LinkSuccess
import com.pacemckinney.tally.ui.AppRoot
import com.pacemckinney.tally.ui.Host
import com.pacemckinney.tally.ui.MainViewModel
import com.pacemckinney.tally.ui.TallyTheme
import kotlinx.coroutines.launch

// FragmentActivity (a ComponentActivity) because BiometricPrompt needs it.
class MainActivity : FragmentActivity(), Host {
    private val vm: MainViewModel by viewModels()

    private var linkingState by mutableStateOf(false)
    override val linking: Boolean get() = linkingState
    private var updateItemId: String? = null
    private var pendingSession: PlaidLinkSession? = null

    private var locked by mutableStateOf(false)
    private var stoppedAt = 0L
    private var authInProgress = false

    // Registered as a field so a result returned after process death (UCU login can take a
    // while) is still delivered.
    private val openLink: ActivityResultLauncher<PlaidSession> =
        registerForActivityResult(OpenPlaidLink()) { onLinkResult(it) }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked = vm.repo.prefs.biometricLock && canAuthenticate()
        setContent {
            TallyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (locked) LockScreen() else AppRoot(vm, this@MainActivity)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStart() {
        super.onStart()
        // Re-lock after 60s in the background, but not when coming back from Plaid's screen.
        if (!linkingState && stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > 60_000 &&
            vm.repo.prefs.biometricLock && canAuthenticate()
        ) locked = true
        if (locked) authenticate()
        else if (vm.repo.store.items.isNotEmpty() && System.currentTimeMillis() - vm.repo.prefs.lastSync > 5 * 60_000) {
            vm.refresh(live = false)
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    // ---- Plaid Link ----

    override fun connectBank(updateItemId: String?) {
        if (linkingState) return
        linkingState = true
        this.updateItemId = updateItemId
        lifecycleScope.launch {
            try {
                val linkToken = vm.repo.createLinkToken(updateItemId)
                pendingSession = Plaid.createPlaidLinkSession(
                    this@MainActivity,
                    linkTokenConfiguration {
                        token = linkToken
                        onLoad = OnLoadCallback {
                            pendingSession?.let { openLink.launch(it) }
                            pendingSession = null
                        }
                    },
                )
            } catch (e: Exception) {
                linkingState = false
                vm.toast(friendly(e))
            }
        }
    }

    private fun onLinkResult(result: LinkResult) {
        val updating = updateItemId
        updateItemId = null
        when (result) {
            is LinkSuccess -> lifecycleScope.launch {
                try {
                    if (updating != null) {
                        vm.repo.clearReauth(updating)
                        vm.repo.sync(liveBalances = true)
                        vm.toast("Reconnected")
                    } else {
                        vm.toast("Connected ${result.metadata.institution?.name ?: "your bank"}. Pulling history…")
                        vm.repo.completeLink(result.publicToken, result.metadata.institution?.name)
                        TallyApp.schedule(this@MainActivity, vm.repo.prefs.syncMinutes)
                        TallyApp.followUpSyncs(this@MainActivity)
                    }
                } catch (e: Exception) {
                    vm.toast(friendly(e))
                } finally {
                    linkingState = false
                }
            }
            is LinkExit -> {
                linkingState = false
                result.error?.let { err ->
                    vm.toast(err.displayMessage?.ifBlank { null } ?: err.errorMessage)
                }
            }
            else -> linkingState = false
        }
    }

    override fun rescheduleSync(minutes: Int) = TallyApp.schedule(this, minutes)

    private fun friendly(e: Exception): String {
        val m = listOfNotNull((e as? com.pacemckinney.tally.data.PlaidException)?.errorCode, e.message)
            .joinToString(": ").ifBlank { "Something went wrong" }
        return when {
            "android_package_name" in m || "INVALID_ANDROID_PACKAGE_NAME" in m ->
                "Add com.pacemckinney.tally under Developers → API → Allowed Android package names in the Plaid dashboard."
            "invalid client_id or secret" in m.lowercase() || "INVALID_API_KEYS" in m ->
                "Plaid didn't accept those keys. Check the client_id, secret and Sandbox/Real setting."
            else -> m
        }
    }

    // ---- Lock ----

    private fun canAuthenticate() =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (authInProgress) return
        authInProgress = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authInProgress = false
                locked = false
                if (vm.repo.store.items.isNotEmpty()) vm.refresh(live = false)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { authInProgress = false }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Tally")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    @androidx.compose.runtime.Composable
    private fun LockScreen() {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Tally is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { authenticate() }) { Text("Unlock") }
        }
    }
}
