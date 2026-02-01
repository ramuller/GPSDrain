package net.ramuller.gpsdrain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.ramuller.gpsdrain.util.sendLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket



class GpsClientService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) {
            return START_STICKY
        }
        isRunning = true

        sendLog(applicationContext, "GpsClientService started")

        val port = intent?.getIntExtra("port", 2768) ?: 2768
        val start = intent?.getIntExtra("startOctet", 100) ?: 100
        val end = intent?.getIntExtra("endOctet", 128) ?: 128
        val subnet = intent?.getStringExtra("subnet") ?: "192.168.231"

        scope.launch {
            discoverAndPoll(subnet, start, end, port)
        }

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val channelId = "gps_socket_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "GPS Socket Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("GPS Forwarder Running")
            .setContentText("Listening for socket connections")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 🔁 Change to your icon
            .build()
    }

    private suspend fun discoverAndPoll(subnet: String, start: Int, end: Int, port: Int) {
        for (i in start..end) {
            val ip = "$subnet.$i"
            sendLog(applicationContext, "Trying server $ip:$port")
            try {
                val socket = withTimeoutOrNull(500) {
                    Socket().apply {
                        connect(InetSocketAddress(ip, port),port)
                    }
                }
                if (socket != null) {
                    sendLog(applicationContext, "✅ Found GPS Server at $ip:$port")
                    pollGps(socket)
                    return // exit loop after first success
                }
            } catch (e: Exception) {
                sendLog(applicationContext, "Connect failed : ${e.message}")
                // Ignored
            }
        }
    }
    private suspend fun pollGps(socket: Socket) {
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            while (isRunning) {
                try {
                    writer.println("Give me GPS")
                    val response = reader.readLine()
                    val tmp = response.split(":")[1]
                    val coords = tmp.split(",")
                    if (coords.size == 2) {
                        val lat = coords[0].toDoubleOrNull()
                        val lon = coords[1].toDoubleOrNull()
                        if (lat != null && lon != null) {
                            mockLocation(lat, lon)
                        }
                    }
                    sendLog(applicationContext, "📍 GPS: $coords")

                    delay(1000)
                } catch (e: Exception) {
                    sendLog(applicationContext, "❌ Lost connection: ${e.message}")
                    socket.close()
                    break
                }
            }
        }
    override fun onDestroy() {
        sendLog(applicationContext, "GpsClientService stopping")
        stopForeground(true) // removes notification
        isRunning = false
        super.onDestroy()
    }

   fun mockLocation(lat: Double, lon: Double) {
       val provider = LocationManager.GPS_PROVIDER
       val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

       try {
           try {
               locationManager.removeTestProvider(provider)
           } catch (_: Exception) {}

           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
               // ✅ Android 12+ — use new ProviderProperties API
               val props = ProviderProperties.Builder()
                   .setAccuracy(ProviderProperties.ACCURACY_FINE)
                   .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                   // .setSupportsAltitude(true)
                   // .setSupportsSpeed(true)
                   // .setSupportsBearing(true)
                   .build()

               locationManager.addTestProvider(provider, props, emptySet())
           } else {
               // ✅ Android 9–11 — use legacy addTestProvider
               locationManager.addTestProvider(
                   provider,
                   /* requiresNetwork */ false,
                   /* requiresSatellite */ false,
                   /* requiresCell */ false,
                   /* hasMonetaryCost */ false,
                   /* supportsAltitude */ true,
                   /* supportsSpeed */ true,
                   /* supportsBearing */ true,
                   /* powerRequirement */ Criteria.POWER_LOW,
                   /* accuracy */ Criteria.ACCURACY_FINE
               )
           }

           locationManager.setTestProviderEnabled(provider, true)

           val mockLocation = Location(provider).apply {
               latitude = lat
               longitude = lon
               accuracy = 1.0f
               time = System.currentTimeMillis()
               elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
           }

           locationManager.setTestProviderLocation(provider, mockLocation)

           sendLog(applicationContext, "📍 Mocked: $lat, $lon")

         } catch (e: SecurityException) {
            sendLog(applicationContext, "❌ Mocking failed: ${e.message}")
         } catch (e: Exception) {
           sendLog(applicationContext, "❌ Error mocking: ${e.message}")
         }
    }
}