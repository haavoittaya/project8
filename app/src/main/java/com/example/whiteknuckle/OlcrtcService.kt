package com.example.whiteknuckle

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OlcrtcService : Service() {

    companion object {
        private const val CHANNEL_ID = "olcrtc_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val binaryFile = File(applicationInfo.nativeLibraryDir, "libolcrtc-android.so")
        val binaryPath = binaryFile.absolutePath
        val configFile = File(filesDir, "client.yaml")

        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Olcrtc Tunnel")
                .setContentText("Service is running")
                .setOngoing(true)
                .build()
        )

        if (!binaryFile.exists()) {
            appendLog("Binary not found: $binaryPath")
            stopService()
            return START_NOT_STICKY
        }

        if (!configFile.exists()) {
            appendLog("Config not found: ${configFile.absolutePath}")
            stopService()
            return START_NOT_STICKY
        }

        if (process != null) {
            stopService()
        }

        serviceScope.launch {
            try {
                val configText = configFile.readText()
                _logs.value += "=== CURRENT CLIENT.YAML ==="
                _logs.value += configText
                _logs.value += "==========================="

                val kotlinHttpCode = runCatching {
                    val connection = (URL("https://cloud-api.yandex.ru").openConnection() as HttpURLConnection)
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    try {
                        connection.responseCode
                    } finally {
                        connection.disconnect()
                    }
                }.getOrElse {
                    appendLog("Kotlin HTTP Test Error: ${it.message}")
                    -1
                }
                appendLog("Kotlin HTTP Test Code: $kotlinHttpCode")

                binaryFile.setExecutable(true, false)
                val binaryManager = BinaryManager(this@OlcrtcService)
                val resolvFile = binaryManager.prepareResolvConf()
                val caFile = binaryManager.extractCaBundle()

                appendLog("Config file: ${configFile.absolutePath}, size=${configFile.length()}")
                appendLog("Binary file: $binaryPath, size=${binaryFile.length()}")
                appendLog("CA file: ${caFile.absolutePath}, size=${caFile.length()}")
                appendLog("Resolv file: ${resolvFile.absolutePath}, size=${resolvFile.length()}")
                if (configFile.length() == 0L) {
                    appendLog("Config file is empty")
                    stopService()
                    return@launch
                }

                val pb = ProcessBuilder(binaryPath, "client.yaml")
                val localIp = getLocalIpAddress() ?: "127.0.0.1"
                _logs.value += "Detected Local IP: $localIp"
                val env = pb.environment()
                env["HOME"] = filesDir.absolutePath
                env["TMPDIR"] = cacheDir.absolutePath
                env["PATH"] = "/system/bin:/system/xbin"
                env["SSL_CERT_FILE"] = caFile.absolutePath
                env["RESOLV_CONF"] = resolvFile.absolutePath
                env["GODEBUG"] = "netdns=go"
                env["PION_UDP_BIND_IP"] = localIp
                env["PION_FORCE_IP"] = localIp
                env.entries
                    .sortedBy { it.key }
                    .forEach { entry -> appendLog("ENV ${entry.key}=${entry.value}") }
                _logs.value += "Binary exists: ${File(binaryPath).exists()}"
                _logs.value += "Config exists: ${File(filesDir, "client.yaml").exists()}"
                pb.directory(filesDir)
                pb.redirectErrorStream(true)
                process = pb.start()
                _isRunning.value = true

                val runningProcess = process ?: return@launch

                launch {
                    runningProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { appendLog(it) }
                    }
                }

                val exitCode = runningProcess.waitFor()
                appendLog("Process exited with code $exitCode")
            } catch (e: Exception) {
                appendLog("Failed to start process: ${e.message}")
            } finally {
                process = null
                _isRunning.value = false
                stopSelf()
            }
        }

        return START_STICKY
    }

    fun stopService() {
        process?.destroy()
        process = null
        _isRunning.value = false
        stopSelf()
    }

    override fun onDestroy() {
        stopService()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun appendLog(line: String) {
        _logs.update { it + line }
    }

    private fun getLocalIpAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (networkInterface in interfaces) {
            val addresses = networkInterface.inetAddresses ?: continue
            for (address in addresses) {
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Olcrtc Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
