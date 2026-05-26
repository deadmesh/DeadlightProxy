package boo.deadlight.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

    companion object {
        const val ACTION_STOP = "boo.deadlight.proxy.STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxy()
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForegroundWithType("Starting Deadlight Proxy...", isRunning = true)

        Thread(::startProxy).start()

        return START_STICKY
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
                Log.e(TAG, "Binary not found")
                updateNotification("Error: Binary not found", isRunning = false)
                return
            }

            binary.setExecutable(true)
            val configFile = writeDefaultConfig()

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

            updateNotification("Running • 127.0.0.1:8080", isRunning = true)
            pipeLogsToLogcat()
            monitorProcess()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            updateNotification("Failed to start", isRunning = false)
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
    private fun writeDefaultConfig(): File {
        val conf = File(filesDir, "deadlight.conf")
        if (!conf.exists()) {
            conf.writeText("""
                [core]
                port = 8080
                max_connections = 100
                worker_threads = 2
                log_level = 2

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
        }
        return conf
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
                if (exitCode != 0) {
                    Log.w(TAG, "Proxy process exited with code: $exitCode")
                    updateNotification("Proxy stopped unexpectedly", isRunning = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Process monitoring error", e)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopProxy() {
        proxyProcess?.destroy()
        proxyProcess?.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS) // graceful shutdown
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