package boo.deadlight.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

class ProxyService : Service() {

    private var proxyProcess: Process? = null
    private val TAG = "DeadlightProxy"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "deadlight_proxy_channel"
    private var intentionalStop = false

    companion object {
        const val ACTION_STOP = "boo.deadlight.proxy.STOP"
        const val ACTION_RELOAD_CONFIG = "boo.deadlight.proxy.RELOAD_CONFIG"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                reloadConfig()
                return START_STICKY
            }
            else -> {
                createNotificationChannel()
                startForegroundWithType("Starting Deadlight Proxy...", isRunning = true)
                Thread(::startProxy).start()
                return START_STICKY
            }
        }
    }

    private fun startForegroundWithType(status: String, isRunning: Boolean) {
        val notification = buildNotification(status, isRunning)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                0x40000000  // FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProxy() {
        try {
            val libDir = applicationInfo.nativeLibraryDir
            val binary = File(libDir, "libdeadlight.so")

            if (!binary.exists()) {
                Log.e(TAG, "Binary not found at $libDir")
                updateNotification("Error: Binary not found", isRunning = false)
                return
            }

            binary.setExecutable(true)
            val configFile = ensureConfigExists()
            val port = readPortFromConfig(configFile)

            proxyProcess = ProcessBuilder(
                binary.absolutePath,
                "-c", configFile.absolutePath,
                "-v"
            ).apply {
                environment()["LD_LIBRARY_PATH"] = libDir
                environment()["GIO_MODULE_DIR"] = libDir
                environment()["HOME"] = filesDir.absolutePath
                redirectErrorStream(true)
                redirectOutput(File(filesDir, "proxy.log"))
            }.start()

            updateNotification("Running • 127.0.0.1:$port", isRunning = true)
            Log.i(TAG, "Proxy started with config: ${configFile.absolutePath}")

            pipeLogsToLogcat()
            monitorProcess()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            updateNotification("Failed to start", isRunning = false)
        }
    }

    private fun ensureConfigExists(): File {
        val conf = File(filesDir, "deadlight.conf")

        // Only write defaults on first launch
        if (!conf.exists()) {
            writeDefaultConfig(conf)
        }

        return conf
    }

    private fun writeDefaultConfig(conf: File) {
        try {
            val prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            val port = prefs.getInt(SettingsActivity.KEY_PORT, SettingsActivity.DEFAULT_PORT)
            val threads = prefs.getInt(SettingsActivity.KEY_THREADS, SettingsActivity.DEFAULT_THREADS)
            val maxConn = prefs.getInt(SettingsActivity.KEY_MAX_CONN, SettingsActivity.DEFAULT_MAX_CONN)
            val logLevel = prefs.getString(SettingsActivity.KEY_LOG_LEVEL, SettingsActivity.DEFAULT_LOG_LEVEL)

            conf.writeText("""
                [core]
                port = $port
                max_connections = $maxConn
                worker_threads = $threads
                log_level = $logLevel

                [security]
                auth_secret =

                [ssl]
                enabled = false
                ca_cert_file = ${filesDir.absolutePath}/.deadlight/ca.crt
                ca_key_file = ${filesDir.absolutePath}/.deadlight/ca.key

                [vpn]
                enabled = false

                [plugins]
                enabled = false

                [blog]
                enable_cache = false
            """.trimIndent())

            Log.i(TAG, "Default config written to ${conf.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write default config", e)
        }
    }

    private fun readPortFromConfig(file: File): Int {
        return try {
            file.readLines()
                .find { it.trim().startsWith("port =") }
                ?.substringAfter("=")
                ?.trim()
                ?.toIntOrNull() ?: SettingsActivity.DEFAULT_PORT
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read port from config", e)
            SettingsActivity.DEFAULT_PORT
        }
    }

    private fun reloadConfig() {
        proxyProcess?.let { process ->
            try {
                // Send SIGHUP via kill command
                val pidField = process.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                val pid = pidField.getInt(process)

                Runtime.getRuntime().exec(arrayOf("kill", "-HUP", pid.toString()))
                Log.i(TAG, "Sent SIGHUP to proxy PID $pid")
                updateNotification("Config reloaded", isRunning = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SIGHUP", e)
            }
        }
    }

    private fun updateNotification(status: String, isRunning: Boolean) {
        val notification = buildNotification(status, isRunning)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(status: String, isRunning: Boolean): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = if (isRunning) R.drawable.ic_notification_running else R.drawable.ic_notification_stopped

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Deadlight Proxy")
            .setContentText(status)
            .setSmallIcon(iconRes)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_delete),
                    "Stop Proxy",
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun pipeLogsToLogcat() {
        Thread {
            try {
                proxyProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d(TAG, line)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Log piping ended", e)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun monitorProcess() {
        Thread {
            try {
                val exitCode = proxyProcess?.waitFor() ?: -1
                if (exitCode != 0 && !intentionalStop) {
                    Log.e(TAG, "Proxy process exited with code: $exitCode")
                    updateNotification("Proxy stopped unexpectedly (code: $exitCode)", isRunning = false)
                }
            } catch (e: Exception) {
                if (!intentionalStop) {
                    Log.e(TAG, "Process monitoring error", e)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopProxy() {
        intentionalStop = true
        proxyProcess?.destroy()
        if (proxyProcess?.waitFor(2000, TimeUnit.MILLISECONDS) == false) {
            proxyProcess?.destroyForcibly()
        }
        proxyProcess = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deadlight Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of the local Deadlight proxy server"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}