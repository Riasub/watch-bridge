package dev.watchbridge.galaxy

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleButton: Button

    private val onLogChanged: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        EventLog.addListener(onLogChanged)
        render()
    }

    override fun onPause() {
        EventLog.removeListener(onLogChanged)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // ---------------------------------------------------------------- 화면 구성

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusView = TextView(this).apply { textSize = 15f }
        root.addView(statusView)

        root.addView(button("1. 알림 접근 허용 (카톡 알림 읽기)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("2. 블루투스·알림 권한 허용") { requestRuntimePermissions() })
        root.addView(button("3. 배터리 최적화 예외 설정") { requestBatteryExemption() })
        toggleButton = button("브리지 시작") { toggleBridge() }
        root.addView(toggleButton)
        root.addView(button("테스트 메시지 보내기") { sendTestMessage() })

        root.addView(TextView(this).apply {
            text = "\n기록 (메시지 내용은 기록하지 않습니다)"
            textSize = 13f
        })
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(logView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 동작

    private fun render() {
        val prefix = (if (listenerEnabled()) "✔" else "✘")
        val bt = if (BridgeService.hasBlePermissions(this) && notificationPermissionGranted()) "✔" else "✘"
        val battery = if (batteryExempt()) "✔" else "✘"
        val running = if (BridgeService.running) "실행 중" else "중지됨"
        statusView.text = buildString {
            appendLine("$prefix 카톡 알림 읽기 허용")
            appendLine("$bt 블루투스·알림 권한")
            appendLine("$battery 배터리 최적화 예외")
            appendLine("브리지: $running")
            append("워치 구독: ${BridgeService.connectedCount}대")
        }
        toggleButton.text = if (BridgeService.running) "브리지 중지" else "브리지 시작"
        logView.text = EventLog.snapshot().takeLast(60).asReversed().joinToString("\n")
    }

    private fun listenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { it.startsWith("$packageName/") }
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun requestRuntimePermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
            wanted += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun toggleBridge() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (BridgeService.running) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            stopService(Intent(this, BridgeService::class.java))
        } else {
            if (!BridgeService.hasBlePermissions(this)) {
                requestRuntimePermissions()
                return
            }
            prefs.edit().putBoolean(KEY_ENABLED, true).apply()
            startForegroundService(Intent(this, BridgeService::class.java))
        }
        render()
    }

    private fun sendTestMessage() {
        if (!BridgeService.running) {
            EventLog.add("브리지를 먼저 시작하세요")
            return
        }
        BridgeService.post(
            JSONObject().put("v", 1).put("t", "msg").put("app", "kakao")
                .put("from", "테스트").put("room", "")
                .put("text", "워치 브리지 테스트 메시지입니다 ✔").put("ts", System.currentTimeMillis())
        )
        EventLog.add("테스트 메시지 전송")
    }

    companion object {
        const val PREFS = "bridge"
        const val KEY_ENABLED = "enabled"
    }
}
