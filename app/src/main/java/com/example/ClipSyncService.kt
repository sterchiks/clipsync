package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.data.ClipDatabase
import com.example.data.ClipItem
import com.example.data.ClipServiceTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

class ClipSyncService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var isRunning = false

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "clipsync_foreground_channel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            ClipServiceTracker.setServiceRunning(true)
            
            // Start Foreground Service
            startForeground(NOTIFICATION_ID, buildNotification("ClipSync starting..."))

            // Find local IP Address
            val localIp = getLocalIpAddress()
            ClipServiceTracker.setServerIp(localIp)

            // Update Notification with the IP address info
            updateNotification("Clipboard sync listening on $localIp")

            // Start HTTP Socket Server
            startHttpServer()

            // Start UDP Discovery Server & Broadcaster
            startUdpServer()
            startUdpBroadcaster(localIp)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        ClipServiceTracker.setServiceRunning(false)
        
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startHttpServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(40040).apply {
                    reuseAddress = true
                }
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    serviceScope.launch {
                        handleHttpClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        val database = ClipDatabase.getDatabase(this)
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val firstLine = reader.readLine() ?: return
            
            // Parsing basic HTTP requests
            // POST /sync HTTP/1.1 or GET /status HTTP/1.1
            val parts = firstLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }
            
            val method = parts[0]
            val path = parts[1]
            
            var contentLength = 0
            var line: String? = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }

            if (method.equals("POST", ignoreCase = true) && path == "/sync") {
                // Read content body
                val bodyBuilder = StringBuilder()
                if (contentLength > 0) {
                    val buffer = CharArray(1024)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = reader.read(buffer, 0, Math.min(buffer.size, contentLength - totalRead))
                        if (read == -1) break
                        bodyBuilder.append(buffer, 0, read)
                        totalRead += read
                    }
                }
                
                val textReceived = bodyBuilder.toString()
                if (textReceived.isNotEmpty()) {
                    ClipServiceTracker.setLastReceivedText(textReceived)
                    val senderName = socket.inetAddress.hostAddress ?: "Unknown PC"
                    
                    // Update active PC IP to enable quick easy return-syncing
                    if (ClipServiceTracker.pcIp.value.isEmpty()) {
                        ClipServiceTracker.setPcIp(senderName)
                    }

                    // Write to Local DB History Log
                    serviceScope.launch {
                        database.clipDao.insertClip(
                            ClipItem(
                                text = textReceived,
                                isSent = false,
                                peerName = senderName
                            )
                        )
                    }

                    // Copy to physical Android primary clipboard safely using translucent activity
                    ClipboardHelperActivity.startForCopy(this, textReceived)
                }

                sendHttpResponse(socket, "OK")
            } else if (method.equals("GET", ignoreCase = true) && path == "/status") {
                sendHttpResponse(socket, "ClipSync Service is Running")
            } else {
                sendHttpResponse(socket, "Not Found", 404)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun sendHttpResponse(socket: Socket, body: String, statusCode: Int = 200) {
        try {
            val statusString = when (statusCode) {
                200 -> "200 OK"
                404 -> "404 Not Found"
                else -> "500 Internal Error"
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val outputStream = socket.getOutputStream()
            val header = "HTTP/1.1 $statusString\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            outputStream.write(header.toByteArray(Charsets.UTF_8))
            outputStream.write(bytes)
            outputStream.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startUdpServer() {
        serviceScope.launch {
            try {
                udpSocket = DatagramSocket(40041).apply {
                    reuseAddress = true
                }
                val buffer = ByteArray(1024)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    
                    // Handle message formats: CLIPS_DISCOVERY_PING:PC:192.168.1.10
                    if (message.startsWith("CLIPS_DISCOVERY_PING:")) {
                        val parts = message.split(":")
                        if (parts.size >= 3) {
                            val peerIp = parts[2]
                            ClipServiceTracker.addDiscoveredPeer(peerIp)
                            
                            // Automate PC IP setting for connected users
                            if (ClipServiceTracker.pcIp.value.isEmpty()) {
                                ClipServiceTracker.setPcIp(peerIp)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startUdpBroadcaster(localIp: String) {
        serviceScope.launch {
            var ds: DatagramSocket? = null
            try {
                ds = DatagramSocket().apply { broadcast = true }
                val packetData = "CLIPS_DISCOVERY_PONG:PHONE:$localIp".toByteArray(Charsets.UTF_8)
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(packetData, packetData.size, broadcastAddress, 40041)

                while (isRunning) {
                    ds.send(packet)
                    delay(5000) // Broadcast presence every 5 seconds to support auto pairing
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                ds?.close()
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: ""
                        if (ip.indexOf(':') < 0 && (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "Unknown"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "ClipSync Status Channel"
            val descriptionText = "Displays real-time background local clipboard listener statuses"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopSelfIntent = Intent(this, ClipSyncService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val pStopIntent = PendingIntent.getService(
            this, 1, stopSelfIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClipSync Clipboard Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pIntent)
            .setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sync", pStopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }
}
