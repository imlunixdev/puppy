package com.lunixdev.puppyterminal

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Switch
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var terminal: TerminalView
    private lateinit var pty: PtySession
    private lateinit var notice: TextView
    private lateinit var setupPanel: LinearLayout
    private lateinit var setupMessage: TextView
    private lateinit var setupButton: Button
    private lateinit var accessory: LinearLayout
    private val bootstrapExecutor = Executors.newSingleThreadExecutor()
    private val rootfsManager by lazy { RootfsManager(this) }
    private var ctrlNext = false
    private var altNext = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xff111318.toInt()
        window.navigationBarColor = 0xff111318.toInt()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff111318.toInt()) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10)); setBackgroundColor(0xff191c22.toInt())
        }
        val mark = TextView(this).apply { text = "◖"; textSize = 22f; setTextColor(0xfff0c674.toInt()) }
        val title = TextView(this).apply { setText(R.string.app_name); textSize = 18f; setTextColor(0xfff2f3f5.toInt()); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); setPadding(dp(8),0,0,0) }
        header.addView(mark); header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val shellSettings = TextView(this).apply {
            text = "⋯"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(0xffc8ccd4.toInt())
            setPadding(dp(12), 0, dp(4), 0); contentDescription = getString(R.string.shell_settings)
            setOnClickListener { showShellSettings() }
        }
        header.addView(shellSettings, LinearLayout.LayoutParams(dp(42), -1))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))
        notice = TextView(this).apply {
            setText(R.string.private_linux_notice)
            textSize = 12f; setTextColor(0xffabb2bf.toInt()); setPadding(dp(18), dp(8), dp(18), dp(8)); setBackgroundColor(0xff191c22.toInt())
        }
        root.addView(notice, LinearLayout.LayoutParams(-1, -2))
        setupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(20)); setBackgroundColor(0xff111318.toInt())
        }
        val setupTitle = TextView(this).apply { setText(R.string.prepare_alpine); textSize = 21f; setTextColor(0xfff2f3f5.toInt()); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val setupDescription = TextView(this).apply {
            setText(R.string.setup_description)
            textSize = 14f; setTextColor(0xffabb2bf.toInt()); setPadding(0, dp(10), 0, dp(14))
        }
        setupMessage = TextView(this).apply { setText(R.string.bundled_linux_image); textSize = 12f; setTextColor(0xff98c379.toInt()); setPadding(0, 0, 0, dp(10)) }
        setupButton = Button(this).apply {
            setText(R.string.install_linux_environment); isAllCaps = false
            setOnClickListener { beginBootstrap() }
        }
        setupPanel.addView(setupTitle); setupPanel.addView(setupDescription); setupPanel.addView(setupMessage)
        setupPanel.addView(setupButton, LinearLayout.LayoutParams(-1, dp(48)))
        root.addView(setupPanel, LinearLayout.LayoutParams(-1, 0, 1f))
        terminal = TerminalView(this, { sendInput(it) }, { rows, cols -> pty.resize(rows, cols) })
        terminal.visibility = View.GONE
        root.addView(terminal, LinearLayout.LayoutParams(-1, 0, 1f))
        accessory = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(6), dp(5), dp(6)); setBackgroundColor(0xff191c22.toInt())
        }
        listOf("ESC" to "\u001b", "CTRL" to "", "ALT" to "", "TAB" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C").forEach { (label, sequence) ->
            val key = Button(this).apply {
                text = label; textSize = 11f; isAllCaps = false; minWidth = 0; minimumWidth = 0
                setPadding(dp(6), 0, dp(6), 0); setTextColor(0xffc8ccd4.toInt()); setBackgroundColor(0xff252932.toInt())
                setOnClickListener { clicked ->
                    when (label) {
                        "CTRL" -> { ctrlNext = !ctrlNext; clicked.alpha = if (ctrlNext) 1f else .72f }
                        "ALT" -> { altNext = !altNext; clicked.alpha = if (altNext) 1f else .72f }
                        else -> pty.send(sequence)
                    }
                    terminal.requestFocus()
                }
            }
            accessory.addView(key, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
        accessory.visibility = View.GONE
        root.addView(accessory, LinearLayout.LayoutParams(-1, dp(50)))
        pty = PtySession({ terminal.append(it) }, { showError(it) })
        setContentView(root)
        if (rootfsManager.isReady()) openLinuxTerminal() else setupPanel.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        notice.text = getString(R.string.terminal_error, message); notice.setTextColor(0xffe06c75.toInt())
        if (::setupPanel.isInitialized && setupPanel.visibility == View.VISIBLE) {
            setupMessage.text = message; setupMessage.setTextColor(0xffe06c75.toInt())
            setupButton.isEnabled = true; setupButton.setText(R.string.retry_installation)
        } else Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
    private fun beginBootstrap() {
        setupButton.isEnabled = false; setupButton.setText(R.string.preparing)
        bootstrapExecutor.execute {
            try {
                rootfsManager.prepare { text, percent -> runOnUiThread { if (!isFinishing) setupMessage.text = if (percent > 0) getString(R.string.install_progress, text, percent) else text } }
                runOnUiThread { if (!isFinishing) openLinuxTerminal() }
            } catch (error: Exception) {
                val message = error.message ?: "Could not prepare Alpine Linux"
                runOnUiThread { if (!isFinishing) showError(message) }
            }
        }
    }
    private fun openLinuxTerminal() {
        setupPanel.visibility = View.GONE; terminal.visibility = View.VISIBLE; accessory.visibility = View.VISIBLE
        notice.setText(R.string.linux_ready_notice)
        notice.setTextColor(0xffabb2bf.toInt())
        if (!::pty.isInitialized) return
        terminal.append(getString(R.string.terminal_banner, "0.1.0", "3.22.6") + "\r\n")
        terminal.append(getString(R.string.simulated_root_notice) + "\r\n\r\n")
        val enhancementsEnabled = getSharedPreferences("puppy-settings", MODE_PRIVATE).getBoolean("shell-integration", true)
        bootstrapExecutor.execute {
            try { pty.start(rootfsManager.runtime(), enhancementsEnabled) }
            catch (e: Exception) {
                val message = "Could not start Alpine shell: ${e.message ?: "unknown error"}"
                runOnUiThread { if (!isFinishing) showError(message) }
            }
        }
        terminal.requestFocus()
        terminal.postDelayed({ terminal.requestFocus(); val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager; imm.showSoftInput(terminal, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }, 350)
    }
    private fun showShellSettings() {
        val preferences = getSharedPreferences("puppy-settings", MODE_PRIVATE)
        val toggle = Switch(this).apply {
            setText(R.string.shell_integration_toggle)
            isChecked = preferences.getBoolean("shell-integration", true)
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.shell_settings)
            .setMessage(R.string.shell_integration_description)
            .setView(toggle)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                preferences.edit().putBoolean("shell-integration", toggle.isChecked).apply()
                Toast.makeText(this, R.string.shell_setting_saved, Toast.LENGTH_LONG).show()
            }
            .show()
    }
    private fun sendInput(value: String) {
        terminal.buffer.noteInput(value)
        var result = value
        if (ctrlNext) {
            ctrlNext = false
            if (value.length == 1) result = (value[0].uppercaseChar().code and 0x1f).toChar().toString()
        }
        if (altNext) { altNext = false; result = "\u001b$result" }
        pty.send(result)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { bootstrapExecutor.shutdownNow(); if (::pty.isInitialized) pty.close(); super.onDestroy() }
}
