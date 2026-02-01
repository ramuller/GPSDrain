package net.ramuller.gpsdrain

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.NetworkCapabilities
import android.net.Network
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*           // ✅ for OutlinedTextField, Button, Text
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ramuller.gpsdrain.util.LOG_ACTION
import com.ramuller.gpsdrain.util.LOG_EXTRA
import com.ramuller.gpsdrain.util.sendLog
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.text.get

lateinit var appContext: Context

class MainActivity : ComponentActivity() {
    private val pendingStartParams = mutableStateOf<StartParams?>(null)

    data class StartParams(val port: Int, val start: Int, val end: Int, val subnet: String)

    // private fun startGpsService(context: Context, port: Int, start: Int, end: Int, subnet: String) {
    fun startGpsService(port: Int, start: Int, end: Int, subnet: String) {
        val intent = Intent(this, GpsClientService::class.java).apply {
            putExtra("port", port)
            putExtra("startOctet", start)
            putExtra("endOctet", end)
            putExtra("subnet", subnet)
        }
        startForegroundService(intent)
    }

    private fun stopGpsService() {
        val intent = Intent(this, GpsClientService::class.java)
        stopService(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext  // Store it globally
        enableEdgeToEdge()
        setContent {
            GPSDrainTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GPSDrain(
                        onStartClicked = { port, start, end, subnet ->
                            startGpsService(port, start, end, subnet)
                        },
                        onStopClicked = {
                            stopGpsService()
                        }
                    )
                }
            }
        }
    }
}
fun requestBatteryOptimizationExemption(context: Context) {
    val packageName = context.packageName
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        context.startActivity(intent)
    }
}
fun testOutgoingNet() {
    val ip = "192.168.231.128"
    val port = 2768
    sendLog(appContext, "Try outgoing network")
    try {
        val socket = Socket()
        val address = InetSocketAddress(ip, port)
        socket.connect(address, 10000)
        // socket.connect(InetSocketAddress("192.168.231.107", 2768), 1000)
    } catch (e: Exception) {
        sendLog(appContext, "Hard coded faile: ${e.message}")
    }
}

fun getWifiSubnetPrefix(context: Context): String {
    val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNet = connMgr.activeNetworkInfo
    sendLog(context, "🔌 Active network: ${activeNet?.typeName}")
    return try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        val ipString = Formatter.formatIpAddress(ipInt)
        sendLog(context, "📶 Detected Wi-Fi IP: $ipString")
        ipString.substringBeforeLast(".")
    } catch (e: Exception) {
        sendLog(context, "❌ Failed via WifiManager: ${e.message}")
        "0.0.0"
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
        sendLog(appContext, "Subnet detection failed: ${e.message}")
        "0.0.0"
    }
}

@Composable
fun GPSDrain(
    context: Context = LocalContext.current,
    onStartClicked: (Int, Int, Int, String) -> Unit,
    onStopClicked: () -> Unit
) {

    val prefs = context.getSharedPreferences("gps_drain_config", Context.MODE_PRIVATE)

    var portText by remember { mutableStateOf(prefs.getInt("port", 2768).toString()) }
    var startOctetText by remember { mutableStateOf(prefs.getInt("startOctet", 100).toString()) }
    var endOctetText by remember { mutableStateOf(prefs.getInt("endOctet", 129).toString()) }
    var subnetPrefix by remember { mutableStateOf("192.168.231") }
    var subnetPrefixText by remember { mutableStateOf(prefs.getInt("subnetPrefix", 128).toString()) }
    var gpsServiceRunning by remember { mutableStateOf(false) }

    val logMessages = remember { mutableStateListOf<String>() }

    // ✅ Define a pending parameter holder (pick ONE way)
    data class PendingParams(val port: Int, val start: Int, val end: Int, val subnet: String)
    val pendingParams = remember { mutableStateOf<PendingParams?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        sendLog(context, "✅ Permission granted status $granted")
        if (granted) {
            pendingParams.value?.let {
                sendLog(context, "✅ Permission granted, starting service")
                onStartClicked(it.port, it.start, it.end, it.subnet)
                gpsServiceRunning = true
            }

        } else {
            sendLog(context, "Location permission denied")
        }
    }



//    val locationPermissionLauncher =
//        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
//            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
//                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
//
//            if (granted) {
//                pendingIntentParams.value?.let { intent ->
//                    context.startForegroundService(intent)
//                    sendLog(context, "Started GPS service")
//                }
//            } else {
//                sendLog(context, "Permission denied")
//            }
//        }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val msg = intent?.getStringExtra(LOG_EXTRA)
                if (msg != null) {
                    logMessages.add(msg)
                }
            }
        }
        val manager = LocalBroadcastManager.getInstance(context)
        manager.registerReceiver(receiver, IntentFilter(LOG_ACTION))

        onDispose {
            manager.unregisterReceiver(receiver)
        }
    }

    // Detect subnet prefix on first launch
    LaunchedEffect(Unit) {
        testOutgoingNet()
        subnetPrefix = getWifiSubnetPrefix(context)
        // subnetPrefix = getLocalSubnetPrefix()
        requestBatteryOptimizationExemption(context)
    }

    val listState = rememberLazyListState()

    LaunchedEffect(logMessages.size) {
        listState.animateScrollToItem(logMessages.size)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        ) {
            item {
                Text("Log Output:", style = MaterialTheme.typography.titleMedium)
            }
            items(logMessages.size) { index ->
                Text(logMessages[index], style = MaterialTheme.typography.bodySmall)
            }
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
                    value = subnetPrefixText,
                    onValueChange = {
                        if (it.length <= 3 && it.all { c -> c.isDigit() }) startOctetText = it
                    },
                    label = { Text("Subnet prefix") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
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

                    sendLog(context, "Start/Stop tapped")
                    // val intent = Intent(context, GpsClientService::class.java)
                    if (port != null && startOctet != null && endOctet != null &&
                        port in 1..65535 && startOctet in 1..254 && endOctet in 1..254 && startOctet <= endOctet
                    ) {
                        sendLog(context, "In start stop" + gpsServiceRunning)
                        if (gpsServiceRunning) {
                            sendLog(context, "Stopping GpsClientService")
                            onStopClicked()
                            gpsServiceRunning = false
                        } else {
                            sendLog(context, "Starting GpsClientService")
                            prefs.edit()
                                .putInt("port", port)
                                .putInt("startOctet", startOctet)
                                .putInt("endOctet", endOctet)
                                .apply()

                            // Save parameters to be used after permission is granted
                            pendingParams.value = PendingParams(port, startOctet, endOctet, subnetPrefix)
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
//                            onStartClicked(port, startOctet, endOctet, subnetPrefix)
                            // logMessages = logMessages + "✅ Port: $port | Range: $subnetPrefix.$startOctet to $subnetPrefix.$endOctet"
                        }
                    } else {
                        sendLog(context, "Some parameters invalid")
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
            ) {
                Text(if (gpsServiceRunning) "Stop" else "Start")
                // Text("Start/Stop")

            }
        }
    }
}

private fun Intent.getStringExtra(value: Any) {}


@Preview(showBackground = true)
@Composable
fun GPSDrainPreview() {
    GPSDrainTheme {
        GPSDrain(
            onStartClicked = { _, _, _, _ -> },
            onStopClicked = { }
        )
    }
}