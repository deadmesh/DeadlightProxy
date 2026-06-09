package boo.deadlight.proxy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    // ── UI refs ──────────────────────────────────────────────────
    private lateinit var portField: EditText
    private lateinit var threadsField: EditText
    private lateinit var maxConnField: EditText
    private lateinit var logLevelSpinner: Spinner
    private lateinit var viewModeSpinner: Spinner

    companion object {
        private val COLOR_BG      = 0xFF080808.toInt()
        private val COLOR_SURFACE = 0xFF111111.toInt()
        private val COLOR_TEXT    = 0xFFC8C8BC.toInt()
        private val COLOR_DIM     = 0xFF555555.toInt()
        private val COLOR_ACCENT  = 0xFF4AB8D4.toInt()
        private val COLOR_OK      = 0xFF44CC88.toInt()

        // SharedPreferences keys
        const val PREFS_NAME       = "deadlight_prefs"
        const val KEY_PORT         = "port"
        const val KEY_THREADS      = "worker_threads"
        const val KEY_MAX_CONN     = "max_connections"
        const val KEY_LOG_LEVEL    = "log_level"
        const val KEY_VIEW_MODE    = "view_mode"

        // View mode values
        const val VIEW_TERMINAL   = "TERMINAL"
        const val VIEW_GUILLOTINE = "GUILLOTINE"

        // Defaults
        const val DEFAULT_PORT      = 8080
        const val DEFAULT_THREADS   = 8
        const val DEFAULT_MAX_CONN  = 500
        const val DEFAULT_LOG_LEVEL = "info"
        const val DEFAULT_VIEW_MODE = VIEW_TERMINAL
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(COLOR_BG)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(40, 100, 40, 60)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(root)

        // ── Title ─────────────────────────────────────────────────
        root.addView(monoText("D E A D L I G H T  //  S E T T I N G S", 14f, COLOR_TEXT).apply {
            letterSpacing = 0.15f
            setPadding(0, 0, 0, 32)
        })

        // ── PROXY section ─────────────────────────────────────────
        root.addView(sectionLabel("PROXY"))

        portField = inputRow(
            root,
            label   = "PORT",
            hint    = "8080",
            value   = prefs.getInt(KEY_PORT, DEFAULT_PORT).toString(),
            numeric = true
        )

        threadsField = inputRow(
            root,
            label   = "WORKER THREADS",
            hint    = "8",
            value   = prefs.getInt(KEY_THREADS, DEFAULT_THREADS).toString(),
            numeric = true
        )

        maxConnField = inputRow(
            root,
            label   = "MAX CONNECTIONS",
            hint    = "500",
            value   = prefs.getInt(KEY_MAX_CONN, DEFAULT_MAX_CONN).toString(),
            numeric = true
        )

        // ── LOGGING section ───────────────────────────────────────
        root.addView(sectionLabel("LOGGING"))

        val logLevels = arrayOf("debug", "info", "warning", "error")
        val savedLevel = prefs.getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL
        logLevelSpinner = spinnerRow(root, "LOG LEVEL", logLevels, savedLevel)

        // ── APPEARANCE section ────────────────────────────────────
        root.addView(sectionLabel("APPEARANCE"))

        val viewModes = arrayOf(VIEW_TERMINAL, VIEW_GUILLOTINE)
        val savedMode = prefs.getString(KEY_VIEW_MODE, DEFAULT_VIEW_MODE) ?: DEFAULT_VIEW_MODE
        viewModeSpinner = spinnerRow(root, "VIEW MODE", viewModes, savedMode)

        // ── Buttons ───────────────────────────────────────────────
        root.addView(buildButtonRow())

        setContentView(scroll)
    }

    private fun save() {
        val portStr = portField.text.toString().trim()
        val threadsStr = threadsField.text.toString().trim()
        val maxConnStr = maxConnField.text.toString().trim()

        // Validate port
        val port = portStr.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            Toast.makeText(this, "Port must be 1024-65535", Toast.LENGTH_SHORT).show()
            portField.requestFocus()
            return
        }

        // Validate threads
        val threads = threadsStr.toIntOrNull()
        if (threads == null || threads !in 1..32) {
            Toast.makeText(this, "Threads must be 1-32", Toast.LENGTH_SHORT).show()
            threadsField.requestFocus()
            return
        }

        // Validate max connections
        val maxConn = maxConnStr.toIntOrNull()
        if (maxConn == null || maxConn !in 10..10000) {
            Toast.makeText(this, "Max connections must be 10-10000", Toast.LENGTH_SHORT).show()
            maxConnField.requestFocus()
            return
        }

        val logLevel = logLevelSpinner.selectedItem.toString()
        val viewMode = viewModeSpinner.selectedItem.toString()

        // Persist to SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.edit().apply {
            putInt(KEY_PORT, port)
            putInt(KEY_THREADS, threads)
            putInt(KEY_MAX_CONN, maxConn)
            putString(KEY_LOG_LEVEL, logLevel)
            putString(KEY_VIEW_MODE, viewMode)
        }.commit()  // ← commit() returns Boolean

        if (!saved) {
            Toast.makeText(this, "Failed to save preferences", Toast.LENGTH_LONG).show()
            return
        }

        // Write config file
        if (!writeConfig(port, threads, maxConn, logLevel)) {
            Toast.makeText(this, "Failed to write config file", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        signalProxyReload()

        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun signalProxyReload() {
        try {
            val intent = Intent(this, ProxyService::class.java).apply {
                action = ProxyService.ACTION_RELOAD_CONFIG
            }
            startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("Settings", "Failed to signal proxy reload", e)
        }
    }

    private fun writeConfig(port: Int, threads: Int, maxConn: Int, logLevel: String): Boolean {
        return try {
            val confFile = java.io.File(filesDir, "deadlight.conf")
            val confText = """
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
        """.trimIndent()

            confFile.writeText(confText)
            android.util.Log.i("Settings", "Config written to ${confFile.absolutePath}")
            true
        } catch (e: Exception) {
            android.util.Log.e("Settings", "Failed to write config", e)
            false
        }
    }

    private fun sectionLabel(text: String): TextView =
        monoText(text, 9f, COLOR_DIM).apply {
            letterSpacing = 0.25f
            setPadding(0, 24, 0, 8)
        }

    private fun inputRow(
        parent: LinearLayout,
        label: String,
        hint: String,
        value: String,
        numeric: Boolean = false
    ): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_SURFACE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }

        row.addView(monoText(label, 11f, COLOR_TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val field = EditText(this).apply {
            setText(value)
            this.hint = hint
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(COLOR_ACCENT)
            setHintTextColor(COLOR_DIM)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            gravity = Gravity.END
            if (numeric) inputType = EditorInfo.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 16 }
            minWidth = 120
        }
        row.addView(field)
        parent.addView(row)
        return field
    }

    private fun spinnerRow(
        parent: LinearLayout,
        label: String,
        options: Array<String>,
        selected: String
    ): Spinner {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_SURFACE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }

        row.addView(monoText(label, 11f, COLOR_TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val spinner = Spinner(this).apply {
            val adapter = object : ArrayAdapter<String>(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                options
            ) {
                override fun getView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup
                ): android.view.View {
                    return monoText(options[position], 11f, COLOR_ACCENT).apply {
                        gravity = Gravity.END
                        setPadding(0, 0, 0, 0)
                    }
                }

                override fun getDropDownView(
                    position: Int,
                    convertView: android.view.View?,
                    parent: android.view.ViewGroup
                ): android.view.View {
                    return monoText(options[position], 11f, COLOR_TEXT).apply {
                        setBackgroundColor(COLOR_SURFACE)
                        setPadding(24, 20, 24, 20)
                    }
                }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            setAdapter(adapter)
            setSelection(options.indexOf(selected).coerceAtLeast(0))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(spinner)
        parent.addView(row)
        return spinner
    }

    private fun buildButtonRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }

            addView(Button(this@SettingsActivity).apply {
                text = "SAVE"
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_OK)
                setBackgroundColor(COLOR_SURFACE)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginEnd = 8 }
                setOnClickListener { save() }
            })

            addView(Button(this@SettingsActivity).apply {
                text = "CANCEL"
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_DIM)
                setBackgroundColor(COLOR_SURFACE)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            })
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun monoText(text: String, sizeSp: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            typeface = Typeface.MONOSPACE
            setTextColor(color)
        }
}