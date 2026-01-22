package net.ramuller.gpsdrain

import android.R.attr.port
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.ramuller.gpsdrain.ui.theme.GPSDrainTheme
import androidx.compose.runtime.*             // ✅ for remember, mutableStateOf, by
import androidx.compose.foundation.layout.*   // ✅ for Column, Modifier, padding, etc.
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*           // ✅ for OutlinedTextField, Button, Text
import androidx.compose.ui.platform.LocalContext
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ramuller.gpsdrain.util.LOG_ACTION
import com.ramuller.gpsdrain.util.LOG_EXTRA
import com.ramuller.gpsdrain.util.sendLog
import java.net.NetworkInterface
import java.net.Inet4Address

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GPSDrainTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GPSDrain(

                    )
                }
            }
        }
    }
}

fun getLocalSubnetPrefix(): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            val addrs = intf.inetAddresses
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val ip = addr.hostAddress
                    return ip.substringBeforeLast(".")
                }
            }
        }
        "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }
}

@Composable
fun GPSDrain(context: Context = LocalContext.current) {
    val prefs = context.getSharedPreferences("gps_drain_config", Context.MODE_PRIVATE)

    var portText by remember { mutableStateOf(prefs.getInt("port", 2768).toString()) }
    var startOctetText by remember { mutableStateOf(prefs.getInt("startOctet", 113).toString()) }
    var endOctetText by remember { mutableStateOf(prefs.getInt("endOctet", 128).toString()) }
    var subnetPrefix by remember { mutableStateOf("10.168.231") }
    // Logging
    val context = LocalContext.current
    val logMessages = remember { mutableStateListOf<String>() }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msg = intent?.getStringExtra(LOG_EXTRA) ?: return
                logMessages.add(msg)
            }
        }
        val manager = LocalBroadcastManager.getInstance(context)
        manager.registerReceiver(receiver, IntentFilter(LOG_ACTION))

        onDispose {
            manager.unregisterReceiver(receiver)
        }
    }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
       logMessages.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    // Detect subnet prefix on first launch
    LaunchedEffect(Unit) {
        subnetPrefix = getLocalSubnetPrefix()
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Log Area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        ) {
            Text("Log Output:", style = MaterialTheme.typography.titleMedium)
            logMessages.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Config Area
        Column(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = portText,
                onValueChange = {
                    if (it.length <= 5 && it.all { c -> c.isDigit() }) portText = it
                },
                label = { Text("Current Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startOctetText,
                    onValueChange = {
                        if (it.length <= 3 && it.all { c -> c.isDigit() }) startOctetText = it
                    },
                    label = { Text("Start Octet") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = endOctetText,
                    onValueChange = {
                        if (it.length <= 3 && it.all { c -> c.isDigit() }) endOctetText = it
                    },
                    label = { Text("End Octet") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                    val startOctet = startOctetText.toIntOrNull()
                    val endOctet = endOctetText.toIntOrNull()
                    sendLog(context, "Save & scan tapped")
                    if (port != null && startOctet != null && endOctet != null &&
                        port in 1..65535 && startOctet in 1..254 && endOctet in 1..254 && startOctet <= endOctet
                    ) {
                        prefs.edit()
                            .putInt("port", port)
                            .putInt("startOctet", startOctet)
                            .putInt("endOctet", endOctet)
                            .apply()

                        // logMessages = logMessages + "✅ Port: $port | Range: $subnetPrefix.$startOctet to $subnetPrefix.$endOctet"
                        // TODO: Start scanning here
                        val intent = Intent(context, GpsClientService::class.java)
                        context.startForegroundService(intent)
                    } else {
                        // logMessages = logMessages + "❌ Invalid input"
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
            ) {
                Text("Start/Stop")
            }
        }
    }
}

private fun Intent.getStringExtra(value: Any) {}


@Preview(showBackground = true)
@Composable
fun GPSDrainPreview() {
    GPSDrainTheme {
        GPSDrain()
    }
}