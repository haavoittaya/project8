package com.example.whiteknuckle

import android.app.NotificationChannel
import java.net.InetAddress
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.system.OsConstants
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.whiteknuckle.action.START_VPN"
        const val ACTION_STOP = "com.example.whiteknuckle.action.STOP_VPN"

        private const val TAG = "LocalVpnService"
        private const val CHANNEL_ID = "local_vpn_channel"
        private const val NOTIFICATION_ID = 2001
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_PREFIX = 24
        private const val VPN_DNS = "198.18.0.2"
        private const val TUNNEL_MTU = 1500
        private const val SOCKS_HOST = "127.0.0.1"
        private const val SOCKS_PORT = 8808
        private const val SOCKET_CHECK_TIMEOUT_MS = 3000

        private const val DEFAULT_USERNAME = "admin"
        private const val DEFAULT_PASSWORD = "123456"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startTunnelIfNeeded()
        }
        return START_STICKY
    }

    private fun startTunnelIfNeeded() {
        if (!OlcrtcService.isRunning.value) {
            Log.e(TAG, "OlcrtcService is not running! Start Go proxy first.")
            stopSelf()
            return
        }

        if (tunnelJob?.isActive == true) return

        createNotificationChannel()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(getString(R.string.vpn_notification_title))
                .setContentText(getString(R.string.vpn_notification_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        )

        tunnelJob = serviceScope.launch {
            try {
                // 1. Build VPN interface
                val routingPreferences = getSharedPreferences(
                    "vpn_routing",
                    Context.MODE_PRIVATE
                )

                val routingMode = routingPreferences.getString(
                    "mode",
                    "all"
                ) ?: "all"

                val selectedApps = routingPreferences.getStringSet(
                    "selected_apps",
                    emptySet()
                ) ?: emptySet()

                Log.i(
                    TAG,
                    "VPN routing mode=$routingMode selectedApps=$selectedApps"
                )

                val builder = Builder()
                    .setSession(getString(R.string.vpn_session_name))
                    .setMtu(TUNNEL_MTU)
                    .addAddress(VPN_ADDRESS, VPN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer(VPN_DNS)
                try {
                    when (routingMode) {

                        "include" -> {
                            // В режиме "Только выбранные" можно использовать
                            // ТОЛЬКО addAllowedApplication().
                            //
                            // Само приложение whiteknuckle сюда не попадает,
                            // поэтому оно автоматически остаётся вне VPN.
                            for (appPackage in selectedApps) {
                                try {
                                    builder.addAllowedApplication(appPackage)
                                } catch (e: PackageManager.NameNotFoundException) {
                                    Log.w(
                                        TAG,
                                        "Selected application not found: $appPackage",
                                        e
                                    )
                                }
                            }

                            Log.i(
                                TAG,
                                "VPN include mode: $selectedApps"
                            )
                        }

                        "exclude" -> {
                            // В режиме "Все кроме выбранных" используем
                            // только addDisallowedApplication().
                            for (appPackage in selectedApps) {
                                try {
                                    builder.addDisallowedApplication(appPackage)
                                } catch (e: PackageManager.NameNotFoundException) {
                                    Log.w(
                                        TAG,
                                        "Excluded application not found: $appPackage",
                                        e
                                    )
                                }
                            }

                            // Само приложение всегда исключаем из VPN,
                            // чтобы его собственный SOCKS5 не попал обратно
                            // в этот же VPN.
                            try {
                                builder.addDisallowedApplication(packageName)
                            } catch (e: PackageManager.NameNotFoundException) {
                                throw IllegalStateException(
                                    "Failed to exclude own application from VPN",
                                    e
                                )
                            }

                            Log.i(
                                TAG,
                                "VPN exclude mode: $selectedApps"
                            )
                        }

                        else -> {
                            // "Все приложения"
                            // Всё идёт через VPN, кроме whiteknuckle.
                            try {
                                builder.addDisallowedApplication(packageName)
                            } catch (e: PackageManager.NameNotFoundException) {
                                throw IllegalStateException(
                                    "Failed to exclude own application from VPN",
                                    e
                                )
                            }

                            Log.i(TAG, "VPN routing mode: all")
                        }
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    throw IllegalStateException(
                        "Failed to configure VPN application routing",
                        e
                    )
                }

                tunInterface = builder.establish()
                    ?: throw IllegalStateException("Failed to establish TUN interface")

                // 2. Duplicate and detach file descriptor
                val tunFd = tunInterface?.fd
                    ?: throw IllegalStateException("Failed to get TUN fd")

                Log.i(TAG, "TUN file descriptor: $tunFd")

                // 3. Write config file
                val configFile = writeTunnelConfig(tunFd)   // <-- передаём fd
                val configContent = configFile.readText()
                Log.i(TAG, "Generated config.yml:\n$configContent")

                // 4. Check SOCKS5 proxy
                if (!isSocks5Available()) {
                    Log.e(TAG, "SOCKS5 proxy at $SOCKS_HOST:$SOCKS_PORT is not reachable")
                    throw IllegalStateException("SOCKS5 proxy unavailable")
                }

                testDns()
                delay(500)

                // 5. Проверка файла конфигурации
                Log.i(TAG, "Config file exists: ${configFile.exists()}, can read: ${configFile.canRead()}, size: ${configFile.length()}")
                if (!configFile.exists() || !configFile.canRead() || configFile.length() == 0L) {
                    throw IllegalStateException("Config file is invalid")
                }

                // 6. Устанавливаем переменную окружения для resolv.conf
                val binaryManager = BinaryManager(this@LocalVpnService)
                val resolvFile = binaryManager.prepareResolvConf()
                Os.setenv("RESOLV_CONF", resolvFile.absolutePath, true)
                Log.i(TAG, "RESOLV_CONF set to ${resolvFile.absolutePath}")

                // 7. Перенаправляем stdout и stderr нативного кода в файлы
                val errFile = File(filesDir, "native_err.log")
                val outFile = File(filesDir, "native_out.log")

                val errPfd = ParcelFileDescriptor.open(
                    errFile,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                )
                val outPfd = ParcelFileDescriptor.open(
                    outFile,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                )

                Os.dup2(errPfd.fileDescriptor, OsConstants.STDERR_FILENO)
                Os.dup2(outPfd.fileDescriptor, OsConstants.STDOUT_FILENO)

                // 8. Запуск туннеля – передаём абсолютный путь
                val configPath = configFile.absolutePath
                Log.i(TAG, "Using config path: $configPath")
                _isRunning.value = true

                val exitCode = HevSocks5TunnelBridge.runTunnel(
                    tunFd = tunFd,
                    configPath = configPath
                )
                Log.i(TAG, "hev-socks5-tunnel finished with code $exitCode")

                // Принудительно сбрасываем данные из OS-буфера на диск
                errPfd.fileDescriptor.sync()
                outPfd.fileDescriptor.sync()
                errPfd.close()
                outPfd.close()

                // Логируем вывод
                if (errFile.exists()) {
                    Log.e(TAG, "Native stderr log:\n${errFile.readText()}")
                }
                if (outFile.exists()) {
                    Log.i(TAG, "Native stdout log:\n${outFile.readText()}")
                }

                if (exitCode != 0) {
                    Log.e(TAG, "Tunnel exited with error code $exitCode")
                }

            } catch (e: IllegalStateException) {
                Log.e(TAG, "VPN tunnel failed", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "VPN permission missing", e)
            } catch (e: RuntimeException) {
                Log.e(TAG, "VPN runtime error", e)
            } finally {
                _isRunning.value = false
                closeTun()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun isSocks5Available(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(SOCKS_HOST, SOCKS_PORT), SOCKET_CHECK_TIMEOUT_MS)
                true
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "SOCKS5 connection timeout", e)
            false
        } catch (e: IOException) {
            Log.w(TAG, "SOCKS5 connection failed", e)
            false
        }
    }

    private fun testDns(): Boolean {
        return try {
            val address = InetAddress.getByName("example.com")
            Log.i(TAG, "DNS test: example.com -> ${address.hostAddress}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DNS test failed", e)
            false
        }
    }

    private fun stopTunnel(stopService: Boolean = true) {
        Log.i(TAG, "Stopping VPN tunnel")

        HevSocks5TunnelBridge.stopTunnel()

        _isRunning.value = false

        closeTun()

        tunnelJob = null

        stopForeground(STOP_FOREGROUND_REMOVE)

        if (stopService) {
            stopSelf()
        }
    }



    private fun closeTun() {
        try {
            tunInterface?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to close TUN interface", e)
        } finally {
            tunInterface = null
        }
    }

    override fun onDestroy() {
        stopTunnel(stopService = false)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun writeTunnelConfig(tunFd: Int): File {
        val prefs = getSharedPreferences("olcrtc_prefs", Context.MODE_PRIVATE)
        val username = prefs.getString("username", DEFAULT_USERNAME) ?: DEFAULT_USERNAME
        val password = prefs.getString("password", DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD

        val configFile = File(filesDir, "config.yml")

        configFile.printWriter().use { out ->
            out.println("tunnel:")
            out.println("  name: tun0")
            out.println("  mtu: $TUNNEL_MTU")
            out.println("  ipv4: $VPN_ADDRESS")

            out.println("socks5:")
            out.println("  address: $SOCKS_HOST")
            out.println("  port: $SOCKS_PORT")
            out.println("  udp: 'udp'")

            if (username.isNotEmpty() && password.isNotEmpty()) {
                out.println("  username: '$username'")
                out.println("  password: '$password'")
            }

            out.println("mapdns:")
            out.println("  address: 198.18.0.2")
            out.println("  port: 53")
            out.println("  network: 100.64.0.0")
            out.println("  netmask: 255.192.0.0")
            out.println("  cache-size: 10000")
        }

        return configFile
    }
}