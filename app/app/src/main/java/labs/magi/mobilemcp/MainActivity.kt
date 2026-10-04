package labs.magi.mobilemcp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var hubUrl: EditText
    private lateinit var deviceName: EditText
    private lateinit var token: EditText
    private lateinit var autoConnect: CheckBox
    private lateinit var connect: Button
    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status); log = findViewById(R.id.log)
        hubUrl = findViewById(R.id.hubUrl); deviceName = findViewById(R.id.deviceName); token = findViewById(R.id.token)
        autoConnect = findViewById(R.id.autoConnect); connect = findViewById(R.id.connect)

        hubUrl.setText(Prefs.hubUrl(this).ifEmpty { BuildConfig.DEFAULT_HUB_URL }); deviceName.setText(Prefs.name(this)); token.setText(Prefs.token(this)); autoConnect.isChecked = Prefs.autoConnect(this)
        findViewById<Button>(R.id.appInfo).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        findViewById<Button>(R.id.enableService).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        connect.setOnClickListener {
            save()
            val t = token.text.toString().trim()
            if (t.isNotEmpty() && t.length < 32) { Toast.makeText(this, "Token looks incomplete (${t.length} chars; hub tokens are 32+). Paste it again.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (hubUrl.text.toString().contains(t) || (t.isNotEmpty() && deviceName.text.toString().contains(t))) { Toast.makeText(this, "The token is pasted into the wrong field.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val svc = MobileAccessibilityService.instance
            if (svc == null) { Toast.makeText(this, "Enable the MobileMCP accessibility service first", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (svc.hub.enabled) svc.hub.disconnect() else svc.hub.connect()
            render()
        }
        findViewById<Button>(R.id.battery).setOnClickListener {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) { Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        applyDebugIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); applyDebugIntent(intent) }

    /** Debug builds accept settings via `adb shell am start ... --es hubUrl ws://10.0.2.2:17692`; release builds ignore extras so other apps cannot repoint the hub. */
    private fun applyDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null || !intent.hasExtra("hubUrl")) return
        hubUrl.setText(intent.getStringExtra("hubUrl") ?: "")
        intent.getStringExtra("name")?.let { deviceName.setText(it) }
        intent.getStringExtra("token")?.let { token.setText(it) }
        save()
        if (intent.getBooleanExtra("connect", false)) MobileAccessibilityService.instance?.hub?.connect()
    }

    private fun save() = Prefs.save(this, hubUrl.text.toString(), deviceName.text.toString(), token.text.toString(), autoConnect.isChecked)

    override fun onResume() { super.onResume(); Status.listen(refresh); render() }
    override fun onPause() { super.onPause(); save(); Status.unlisten(refresh) }

    private fun render() {
        val svc = MobileAccessibilityService.instance
        status.text = if (svc == null) "Accessibility service off - enable it in step 1" else Status.state
        connect.text = if (svc?.hub?.enabled == true) "2. Disconnect" else "2. Connect"
        log.text = Status.lines().asReversed().joinToString("\n")
    }
}
