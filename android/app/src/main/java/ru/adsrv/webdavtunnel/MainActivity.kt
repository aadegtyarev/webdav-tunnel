package ru.adsrv.webdavtunnel

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.adsrv.webdavtunnel.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == TunnelService.ACTION_STATUS) {
                render(
                    intent.getStringExtra(TunnelService.EXTRA_STATE).orEmpty(),
                    intent.getStringExtra(TunnelService.EXTRA_MSG).orEmpty()
                )
            }
        }
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startTunnel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        b.editUrl.setText(prefs.getString("url", "https://webdav.yandex.ru"))
        b.editLogin.setText(prefs.getString("login", ""))
        b.editPassword.setText(prefs.getString("password", ""))
        b.editListen.setText(prefs.getString("listen", "127.0.0.1:1080"))

        b.btnToggle.setOnClickListener {
            val running = TunnelService.lastState == TunnelService.STATE_RUNNING ||
                TunnelService.lastState == TunnelService.STATE_CONNECTING
            if (running) stopTunnel() else requestThenStart()
        }

        b.btnBrowser.setOnClickListener {
            if (TunnelService.lastState == TunnelService.STATE_RUNNING) {
                startActivity(Intent(this, BrowserActivity::class.java))
            } else {
                render(TunnelService.STATE_ERROR, getString(R.string.browser_needs_tunnel))
            }
        }

        // level 3: advanced tuning (collapsed by default)
        loadTuningFields()
        b.advHeader.setOnClickListener {
            val show = b.advBody.visibility != android.view.View.VISIBLE
            b.advBody.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            b.advHeader.text = (if (show) "▾ " else "▸ ") + getString(R.string.advanced_title)
        }
        b.advHeader.text = "▸ " + getString(R.string.advanced_title)
        b.btnResetTune.setOnClickListener {
            Settings.resetTune(this)
            loadTuningFields()
        }
    }

    // ── level 3 tuning persistence ─────────────────────────────────────────────────

    private fun loadTuningFields() {
        val t = Settings.Tune
        b.editPollMin.setText(Settings.tuneInt(this, t.POLL_MIN, t.DEF_POLL_MIN).toString())
        b.editPollMax.setText(Settings.tuneInt(this, t.POLL_MAX, t.DEF_POLL_MAX).toString())
        b.editPuts.setText(Settings.tuneInt(this, t.PUTS, t.DEF_PUTS).toString())
        b.editReadMin.setText(Settings.tuneInt(this, t.READ_MIN, t.DEF_READ_MIN).toString())
        b.editReadMax.setText(Settings.tuneInt(this, t.READ_MAX, t.DEF_READ_MAX).toString())
        b.editChunk.setText(Settings.tuneInt(this, t.CHUNK, t.DEF_CHUNK).toString())
        b.editCoalesce.setText(Settings.tuneInt(this, t.COALESCE, t.DEF_COALESCE).toString())
        b.editDial.setText(Settings.tuneInt(this, t.DIAL, t.DEF_DIAL).toString())
        b.editIdle.setText(Settings.tuneInt(this, t.IDLE, t.DEF_IDLE).toString())
        b.editWatchdog.setText(Settings.tuneInt(this, t.WATCHDOG, t.DEF_WATCHDOG).toString())
    }

    private fun fieldInt(et: android.widget.EditText, def: Int): Int =
        et.text.toString().trim().toIntOrNull()?.takeIf { it > 0 } ?: def

    private fun saveTuningFields() {
        val t = Settings.Tune
        Settings.tune(this).edit()
            .putInt(t.POLL_MIN, fieldInt(b.editPollMin, t.DEF_POLL_MIN))
            .putInt(t.POLL_MAX, fieldInt(b.editPollMax, t.DEF_POLL_MAX))
            .putInt(t.PUTS, fieldInt(b.editPuts, t.DEF_PUTS))
            .putInt(t.READ_MIN, fieldInt(b.editReadMin, t.DEF_READ_MIN))
            .putInt(t.READ_MAX, fieldInt(b.editReadMax, t.DEF_READ_MAX))
            .putInt(t.CHUNK, fieldInt(b.editChunk, t.DEF_CHUNK))
            .putInt(t.COALESCE, fieldInt(b.editCoalesce, t.DEF_COALESCE))
            .putInt(t.DIAL, fieldInt(b.editDial, t.DEF_DIAL))
            .putInt(t.IDLE, fieldInt(b.editIdle, t.DEF_IDLE))
            .putInt(t.WATCHDOG, fieldInt(b.editWatchdog, t.DEF_WATCHDOG))
            .apply()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(TunnelService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        render(TunnelService.lastState, TunnelService.lastMsg)
        ui.post(logPoller)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
        ui.removeCallbacks(logPoller)
    }

    private fun requestThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startTunnel()
        }
    }

    private fun startTunnel() {
        val url = b.editUrl.text.toString().trim()
        val login = b.editLogin.text.toString().trim()
        val password = b.editPassword.text.toString()
        val listen = b.editListen.text.toString().trim().ifBlank { "127.0.0.1:1080" }
        if (url.isEmpty()) { render(TunnelService.STATE_ERROR, "Enter WebDAV URL"); return }
        if (login.isEmpty()) { render(TunnelService.STATE_ERROR, "Enter login"); return }
        if (password.isEmpty()) { render(TunnelService.STATE_ERROR, "Enter password"); return }

        saveTuningFields()
        prefs.edit()
            .putString("url", url)
            .putString("login", login)
            .putString("password", password)
            .putString("listen", listen)
            .apply()

        val i = Intent(this, TunnelService::class.java)
            .setAction(TunnelService.ACTION_START)
            .putExtra(TunnelService.EXTRA_URL, url)
            .putExtra(TunnelService.EXTRA_LOGIN, login)
            .putExtra(TunnelService.EXTRA_PASSWORD, password)
            .putExtra(TunnelService.EXTRA_LISTEN, listen)
        ContextCompat.startForegroundService(this, i)
        render(TunnelService.STATE_CONNECTING, "Connecting…")
    }

    private fun stopTunnel() {
        val i = Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP)
        startService(i)
        render(TunnelService.STATE_STOPPED, "Stopped")
    }

    private fun render(state: String, msg: String) {
        b.txtStatus.text = when (state) {
            TunnelService.STATE_RUNNING -> "🟢 $msg"
            TunnelService.STATE_CONNECTING -> "🟡 $msg"
            TunnelService.STATE_ERROR -> "🔴 Error: $msg"
            else -> "⚪ ${msg.ifBlank { "Stopped" }}"
        }
        val running = state == TunnelService.STATE_RUNNING || state == TunnelService.STATE_CONNECTING
        b.btnToggle.text = if (running) "Disconnect" else "Connect"
        b.btnBrowser.isEnabled = state == TunnelService.STATE_RUNNING
        if (state != TunnelService.STATE_RUNNING) b.txtLog.text = ""
    }

    // ── tunnel log tail (surfaces WebDAV errors / 429) ─────────────────────────────

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val logPoller = object : Runnable {
        override fun run() {
            if (TunnelService.lastState == TunnelService.STATE_RUNNING ||
                TunnelService.lastState == TunnelService.STATE_CONNECTING
            ) {
                val logs = runCatching { mobile.Mobile.recentLogs() }.getOrNull().orEmpty()
                b.txtLog.text = logs.lineSequence().toList().takeLast(20).joinToString("\n")
            }
            ui.postDelayed(this, 2500)
        }
    }
}
