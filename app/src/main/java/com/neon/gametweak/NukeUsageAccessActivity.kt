package com.neon.gametweak

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neon.gametweak.ui.theme.NukeEnterpriseTheme

class NukeUsageAccessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var error by remember { mutableStateOf<String?>(null) }
            NukeEnterpriseTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Recent app access", style = MaterialTheme.typography.headlineSmall)
                        Text("App Switch uses Android usage history from the last seven days to show up to four recent launchable apps. This list is processed on your device and is not stored or transmitted by App Switch. Usage access is optional. After granting it, return to the game and tap App Switch again.")
                        Button(onClick = {
                            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
                                .recoverCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                                .onFailure { error = "Usage access settings are unavailable on this device." }
                        }, modifier = androidx.compose.ui.Modifier.nukePressFeedback()) { Text("Continue to Android settings") }
                        OutlinedButton(onClick = { finish() }, modifier = androidx.compose.ui.Modifier.nukePressFeedback()) { Text("Not now") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
