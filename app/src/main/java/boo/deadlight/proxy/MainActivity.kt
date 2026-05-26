package boo.deadlight.proxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.view.View

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var startStopButton: Button
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView

    private lateinit var uptimeText: TextView
    private lateinit var connectionsText: TextView
    private lateinit var bytesText: TextView

    private var logFilter = "ALL"
    private val allLogLines = ArrayDeque<String>(400)
    private var lastLogLine: String? = null
    private var filterButtons = mutableMapOf<String, Button>()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var isRunning = false
    private var pendingStart = false
    private var sseThread: Thread? = null
    private var sseRunning = false
    private val ICON_SIZE_DP = 200
    private var guillotineMode = false
    private lateinit var guillotineRoot: FrameLayout
    private lateinit var guillotineIcon: ImageView
    private var pulseAnimator: android.view.ViewPropertyAnimator? = null

    // Proxy state enum for guillotine icon logic
    private enum class ProxyState { STOPPED, STARTING, RUNNING, STOPPING }
    private var proxyState = ProxyState.STOPPED

    companion object {
        private const val NOTIF_PERMISSION_CODE = 1001
        private const val UI_PORT = 8080
        private const val MAX_LOG_LINES = 400

        private val COLOR_BG      = 0xFF080808.toInt()
        private val COLOR_SURFACE = 0xFF111111.toInt()
        private val COLOR_TEXT    = 0xFFC8C8BC.toInt()
        private val COLOR_DIM     = 0xFF555555.toInt()
        private val COLOR_ACCENT  = 0xFF4AB8D4.toInt()
        private val COLOR_OK      = 0xFF44CC88.toInt()
        private val COLOR_ERR     = 0xFFCC4444.toInt()

        private const val KEY_GUILLOTINE_MODE = "guillotine_mode"

        private const val QUICK_PRESS_TRANSITION_MS = 320L
        private const val SETTLE_TRANSITION_MS = 700L
        private const val PULSE_DURATION_MS = 4200L
        private const val PULSE_SCALE = 1.025f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(COLOR_BG)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(40, 100, 40, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Title
        root.addView(TextView(this).apply {
            text = "D E A D L I G H T  //  P R O X Y"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_TEXT)
            letterSpacing = 0.15f
            setPadding(0, 0, 0, 20)
        })

        statusText = TextView(this).apply {
            text = "● STOPPED"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_ERR)
            setPadding(0, 0, 0, 4)
        }
        root.addView(statusText)

        addressText = TextView(this).apply {
            text = "Proxy: 127.0.0.1:8080"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_DIM)
            setPadding(0, 0, 0, 16)
            setOnClickListener { copyAddress() }
        }
        root.addView(addressText)

        root.addView(buildStatsRow())
        root.addView(buildStartStopButton())
        root.addView(buildLogControls())
        root.addView(buildLogView())

        setContentView(root)

        // Build guillotine view and attach to window
        guillotineRoot = buildGuillotineView()
        val decorFrame = window.decorView as android.widget.FrameLayout
        decorFrame.addView(guillotineRoot)

        restoreSavedUiState(savedInstanceState)

        // Long-press on status dot to enter guillotine mode
        statusText.setOnLongClickListener {
            enterGuillotineMode()
            true
        }

        requestNotificationPermission()
        startSseStream()
        syncRunningState()
    }

    private fun toggleProxy() {
        if (proxyState == ProxyState.STARTING || proxyState == ProxyState.STOPPING) return

        if (isRunning) {
            stopProxyFromUi()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingStart = true
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERMISSION_CODE
                )
            } else {
                doStartProxy()
            }
        }
    }

    private fun restoreSavedUiState(savedInstanceState: Bundle?) {
        guillotineMode = savedInstanceState
            ?.getBoolean(KEY_GUILLOTINE_MODE, false)
            ?: false

        if (guillotineMode) {
            root.visibility = View.GONE
            root.alpha = 0f

            guillotineRoot.visibility = View.VISIBLE
            guillotineRoot.alpha = 1f

            guillotineIcon.animate().cancel()
            guillotineIcon.alpha = 1f
            guillotineIcon.scaleX = 1f
            guillotineIcon.scaleY = 1f
            guillotineIcon.setImageResource(iconForState(proxyState))
        } else {
            root.visibility = View.VISIBLE
            root.alpha = 1f

            guillotineRoot.visibility = View.GONE
            guillotineRoot.alpha = 1f

            stopIconPulse()
        }
    }

    private fun buildStatsRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }

        fun makeCard(label: String, initVal: String): TextView {
            val valueView = TextView(this).apply {
                text = initVal
                textSize = 18f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_DIM)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COLOR_SURFACE)
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = 8 }
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 9f
                    typeface = Typeface.MONOSPACE
                    setTextColor(COLOR_DIM)
                    gravity = Gravity.CENTER_HORIZONTAL
                })
                addView(valueView)
            }
            row.addView(card)
            return valueView
        }

        uptimeText = makeCard("UPTIME", "—")
        connectionsText = makeCard("ACTIVE", "—")
        bytesText = makeCard("TRANSFERRED", "—")

        return row
    }

    private fun buildStartStopButton(): Button {
        startStopButton = Button(this).apply {
            text = "START PROXY"
            typeface = Typeface.MONOSPACE
            setOnClickListener { toggleProxy() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 16 }
        }
        return startStopButton
    }

    private fun buildLogControls(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_SURFACE)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 8 }
        }

        row.addView(TextView(this).apply {
            text = "LOG"
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_SURFACE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
        })

        listOf("ALL", "INFO", "WARN", "ERROR").forEach { filter ->
            val btn = Button(this).apply {
                text = filter
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setPadding(12, 4, 12, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6 }
                setOnClickListener {
                    logFilter = filter
                    updateFilterButtons()
                    refreshLogDisplay()
                }
            }
            filterButtons[filter] = btn
            row.addView(btn)
        }

        updateFilterButtons()
        return row
    }

    private fun buildLogView(): ScrollView {
        logText = TextView(this).apply {
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF00CC88.toInt())
            setBackgroundColor(COLOR_SURFACE)
            setPadding(16, 16, 16, 16)
        }
        logScrollView = ScrollView(this).apply {
            addView(logText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        return logScrollView
    }

    private fun updateFilterButtons() {
        filterButtons.forEach { (filter, btn) ->
            btn.setTextColor(when {
                filter == logFilter && filter == "WARN" -> Color.parseColor("#D4924A")
                filter == logFilter && filter == "ERROR" -> COLOR_ERR
                filter == logFilter -> COLOR_ACCENT
                else -> COLOR_DIM
            })
        }
    }

    private fun passesFilter(line: String): Boolean = when (logFilter) {
        "INFO" -> !line.contains("[DEBUG]")
        "WARN" -> line.contains("[WARN]") || line.contains("[ERROR]")
        "ERROR" -> line.contains("[ERROR]")
        else -> true
    }

    private fun refreshLogDisplay() {
        val filtered = allLogLines.filter { passesFilter(it) }.joinToString("\n")
        logText.text = filtered
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun doStartProxy() {
        setProxyState(
            nextState = ProxyState.STARTING,
            transitionMs = QUICK_PRESS_TRANSITION_MS
        )

        startForegroundService(Intent(this, ProxyService::class.java))

        handler.postDelayed({
            isRunning = true
            setRunningState()
        }, QUICK_PRESS_TRANSITION_MS)
    }

    private fun setRunningState(animated: Boolean = true) {
        statusText.text = "● RUNNING"
        statusText.setTextColor(COLOR_OK)
        startStopButton.text = "STOP PROXY"
        addressText.setTextColor(COLOR_OK)

        isRunning = true
        proxyState = ProxyState.RUNNING

        if (::guillotineIcon.isInitialized) {
            if (animated && guillotineMode) {
                transitionGuillotineIconTo(ProxyState.RUNNING, SETTLE_TRANSITION_MS)
            } else {
                guillotineIcon.animate().cancel()
                guillotineIcon.setImageResource(iconForState(ProxyState.RUNNING))
                guillotineIcon.alpha = 1f
                guillotineIcon.scaleX = 1f
                guillotineIcon.scaleY = 1f
            }
        }

        if (guillotineMode) {
            handler.postDelayed({
                if (guillotineMode && proxyState == ProxyState.RUNNING) {
                    startIconPulse()
                }
            }, if (animated) SETTLE_TRANSITION_MS else 0L)
        }
    }

    private fun setStoppedState(animated: Boolean = true) {
        statusText.text = "● STOPPED"
        statusText.setTextColor(COLOR_ERR)
        startStopButton.text = "START PROXY"
        addressText.setTextColor(COLOR_DIM)

        isRunning = false
        proxyState = ProxyState.STOPPED

        stopIconPulse()

        if (::guillotineIcon.isInitialized) {
            if (animated && guillotineMode) {
                transitionGuillotineIconTo(ProxyState.STOPPED, SETTLE_TRANSITION_MS)
            } else {
                guillotineIcon.animate().cancel()
                guillotineIcon.setImageResource(iconForState(ProxyState.STOPPED))
                guillotineIcon.alpha = 1f
                guillotineIcon.scaleX = 1f
                guillotineIcon.scaleY = 1f
            }
        }
    }

    private fun stableStateForRestore(state: ProxyState): ProxyState {
        return when (state) {
            ProxyState.STARTING -> ProxyState.STOPPED
            ProxyState.STOPPING -> ProxyState.STOPPED
            ProxyState.RUNNING -> ProxyState.RUNNING
            ProxyState.STOPPED -> ProxyState.STOPPED
        }
    }

    private fun stopProxyFromUi() {
        setProxyState(
            nextState = ProxyState.STOPPING,
            transitionMs = QUICK_PRESS_TRANSITION_MS
        )

        stopService(Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        })

        handler.postDelayed({
            isRunning = false
            setStoppedState()
        }, QUICK_PRESS_TRANSITION_MS)
    }

    private fun syncRunningState() {
        Thread {
            val running = isProxyActuallyRunning()

            handler.post {
                if (running) {
                    setRunningState(animated = false)
                } else {
                    setStoppedState(animated = false)
                }
            }
        }.start()
    }

    private fun isProxyActuallyRunning(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$UI_PORT/api/metrics")
                .openConnection() as HttpURLConnection

            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.requestMethod = "GET"

            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun copyAddress() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("proxy", "127.0.0.1:8080"))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIF_PERMISSION_CODE)
        }
    }

    private fun buildGuillotineView(): FrameLayout {
        val density = resources.displayMetrics.density
        val sizePx = (ICON_SIZE_DP * density).toInt()

        guillotineIcon = ImageView(this).apply {
            setImageResource(iconForState(proxyState))
            setBackgroundColor(Color.TRANSPARENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)

            setOnClickListener { toggleProxy() }
            setOnLongClickListener {
                exitGuillotineMode()
                true
            }
        }

        return FrameLayout(this).apply {
            setBackgroundColor(COLOR_BG)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(guillotineIcon)
            visibility = View.GONE
        }
    }

    private fun startSseStream() {
        sseRunning = true
        sseThread = Thread {
            while (sseRunning) {
                try {
                    val conn = URL("http://127.0.0.1:8080/api/stream")
                        .openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 60000
                    conn.setRequestProperty("Accept", "text/event-stream")
                    conn.setRequestProperty("Cache-Control", "no-cache")

                    val reader = conn.inputStream.bufferedReader()
                    var eventType = ""
                    var dataBuffer = StringBuilder()

                    while (sseRunning) {
                        val l = reader.readLine() ?: break
                        when {
                            l.startsWith("event:") -> eventType = l.removePrefix("event:").trim()
                            l.startsWith("data:") -> dataBuffer.append(l.removePrefix("data:").trim())
                            l.isEmpty() && dataBuffer.isNotEmpty() -> {
                                if (eventType == "dashboard") {
                                    handleDashboardEvent(dataBuffer.toString())
                                }
                                eventType = ""
                                dataBuffer = StringBuilder()
                            }
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    android.util.Log.e("ProxySSE", "Stream error", e)
                }
                if (sseRunning) Thread.sleep(3000)
            }
        }.apply { isDaemon = true; start() }
    }
    private fun handleDashboardEvent(data: String) {
        try {
            val json = JSONObject(data)
            val metrics = json.optJSONObject("metrics") ?: return
            val logs = json.optJSONArray("logs") ?: return

            val uptime = metrics.optLong("uptime", 0)
            val active = metrics.optInt("active_connections", 0)
            val bytes = metrics.optLong("bytes_transferred", 0)

            handler.post {
                uptimeText.text = formatUptime(uptime)
                uptimeText.setTextColor(COLOR_ACCENT)
                connectionsText.text = active.toString()
                connectionsText.setTextColor(if (active > 0) COLOR_OK else COLOR_DIM)
                bytesText.text = formatBytes(bytes)

                val newLines = mutableListOf<String>()
                for (i in 0 until logs.length()) {
                    val line = logs.getString(i)
                    if (shouldSkipLog(line)) continue
                    newLines.add(line)
                }

                val last = lastLogLine
                val startIdx = if (last != null) {
                    val idx = newLines.lastIndexOf(last)
                    if (idx != -1) idx + 1 else 0
                } else 0

                val tail = newLines.drop(startIdx)
                if (tail.isEmpty()) return@post

                lastLogLine = newLines.last()

                tail.forEach { line ->
                    if (allLogLines.size >= MAX_LOG_LINES) allLogLines.removeFirst()
                    allLogLines.addLast(line)
                }
                refreshLogDisplay()
            }
        } catch (e: Exception) {
            android.util.Log.e("ProxySSE", "Parse error", e)
        }
    }

    private fun shouldSkipLog(line: String): Boolean =
        line.contains("API metrics endpoint") || line.contains("SSE:")

    private fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${seconds}s"
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIF_PERMISSION_CODE && pendingStart && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingStart = false
            doStartProxy()
        }
    }

    private fun setProxyState(
        nextState: ProxyState,
        transitionMs: Long = 450L
    ) {
        proxyState = nextState

        when (nextState) {
            ProxyState.RUNNING -> {
                transitionGuillotineIconTo(nextState, transitionMs)
                handler.postDelayed({
                    if (guillotineMode && proxyState == ProxyState.RUNNING) {
                        startIconPulse()
                    }
                }, transitionMs)
            }

            ProxyState.STOPPED -> {
                stopIconPulse()
                transitionGuillotineIconTo(nextState, transitionMs)
            }

            ProxyState.STARTING,
            ProxyState.STOPPING -> {
                stopIconPulse()
                transitionGuillotineIconTo(nextState, transitionMs)
            }
        }
    }

    private fun iconForState(state: ProxyState): Int = when (state) {
        ProxyState.STOPPED          -> R.drawable.icon_error      // green outline, transparent bg
        ProxyState.STARTING         -> R.drawable.icon_stopped
        ProxyState.STOPPING         -> R.drawable.ic_notification_stopped
        ProxyState.RUNNING          -> R.drawable.icon_running      // green/pink filled
    }

    private fun startIconPulse() {
        stopIconPulse()

        if (!guillotineMode || proxyState != ProxyState.RUNNING) return

        fun pulse() {
            if (!guillotineMode || proxyState != ProxyState.RUNNING) return

            pulseAnimator = guillotineIcon.animate()
                .scaleX(PULSE_SCALE)
                .scaleY(PULSE_SCALE)
                .setDuration(PULSE_DURATION_MS)
                .withEndAction {
                    if (!guillotineMode || proxyState != ProxyState.RUNNING) return@withEndAction

                    pulseAnimator = guillotineIcon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(PULSE_DURATION_MS)
                        .withEndAction {
                            if (guillotineMode && proxyState == ProxyState.RUNNING) pulse()
                        }

                    pulseAnimator?.start()
                }

            pulseAnimator?.start()
        }

        pulse()
    }

    private fun stopIconPulse() {
        pulseAnimator = null

        if (::guillotineIcon.isInitialized) {
            guillotineIcon.animate().cancel()
            guillotineIcon.scaleX = 1f
            guillotineIcon.scaleY = 1f
            guillotineIcon.alpha = 1f
        }
    }

    private fun enterGuillotineMode() {
        guillotineMode = true

        guillotineIcon.animate().cancel()
        guillotineRoot.animate().cancel()
        root.animate().cancel()

        guillotineRoot.alpha = 0f
        guillotineIcon.alpha = 0f
        guillotineIcon.scaleX = 0.94f
        guillotineIcon.scaleY = 0.94f
        guillotineIcon.setImageResource(iconForState(proxyState))

        guillotineRoot.visibility = View.VISIBLE

        root.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                root.visibility = View.GONE

                guillotineRoot.animate()
                    .alpha(1f)
                    .setDuration(300)
                    .start()

                guillotineIcon.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(450)
                    .withEndAction {
                        if (proxyState == ProxyState.RUNNING) startIconPulse()
                    }
                    .start()
            }
            .start()
    }

    private fun exitGuillotineMode() {
        guillotineMode = false
        stopIconPulse()

        guillotineRoot.animate().alpha(0f).setDuration(350).withEndAction {
            guillotineRoot.visibility = View.GONE
            root.alpha = 0f
            root.visibility = View.VISIBLE
            root.animate().alpha(1f).setDuration(350).start()
        }.start()
    }

    private fun transitionGuillotineIconTo(state: ProxyState, duration: Long = 450L) {
        if (!::guillotineIcon.isInitialized) return

        val next = iconForState(state)

        handler.post {
            guillotineIcon.animate().cancel()

            if (!guillotineMode) {
                guillotineIcon.setImageResource(next)
                guillotineIcon.alpha = 1f
                return@post
            }

            guillotineIcon.animate()
                .alpha(0f)
                .setDuration(duration / 2)
                .withEndAction {
                    guillotineIcon.setImageResource(next)

                    guillotineIcon.animate()
                        .alpha(1f)
                        .setDuration(duration / 2)
                        .start()
                }
                .start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_GUILLOTINE_MODE, guillotineMode)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        sseRunning = false
        sseThread?.interrupt()
        stopIconPulse()
        super.onDestroy()
    }
}