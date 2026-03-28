package fuck.andes

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

internal class DiagnosticActivity : Activity() {

    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostic)

        tvLog = findViewById(R.id.tv_log)
        scrollLog = findViewById(R.id.scroll_log)

        fillDeviceInfo()

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { loadLogs() }
        findViewById<Button>(R.id.btn_copy).setOnClickListener { copyLogs() }

        loadLogs()
    }

    // ── 设备信息 ─────────────────────────────────────────────────────────────

    private fun fillDeviceInfo() {
        val googleInstalled = runCatching {
            packageManager.getPackageInfo(ModuleConfig.GOOGLE_PACKAGE, 0); true
        }.getOrDefault(false)

        val sb = buildString {
            appendLine("Device : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("OS     : Android ${Build.VERSION.RELEASE}  (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Google : ${if (googleInstalled) "✓ 已安装" else "✗ 未安装（必需！）"}")
            appendLine()
            appendLine("LSPosed 必须勾选以下作用域（缺一不可）：")
            appendLine("  • android          ← 系统服务 / 电源键 Hook")
            appendLine("  • com.android.systemui       ← 手势条 Hook")
            appendLine("  • com.google.android.googlequicksearchbox")
            appendLine()
            appendLine("修改作用域后需重启手机！")
        }
        findViewById<TextView>(R.id.tv_device_info).text = sb
    }

    // ── 日志读取 ─────────────────────────────────────────────────────────────

    private fun loadLogs() {
        val btn = findViewById<Button>(R.id.btn_refresh)
        btn.isEnabled = false
        Thread {
            val text = fetchLogcat()
            runOnUiThread {
                tvLog.text = text
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
                btn.isEnabled = true
            }
        }.start()
    }

    private fun fetchLogcat(): String {
        // 1. 直接 logcat（有 READ_LOGS 或 root 时可用）
        val args = arrayOf("logcat", "-d", "-t", "800", "-v", "time", "-s", "${ModuleConfig.TAG}:V")
        val direct = execToString(args)
        if (!direct.isNullOrBlank()) return direct

        // 2. 通过 su 读取（root 设备）
        val suArgs = arrayOf("su", "-c", "logcat -d -t 800 -v time -s ${ModuleConfig.TAG}:V")
        val viaSu = execToString(suArgs)
        if (!viaSu.isNullOrBlank()) return viaSu

        return buildString {
            appendLine("(无法读取 logcat)")
            appendLine()
            appendLine("请用以下 adb 命令手动查看：")
            appendLine("  adb logcat -s ${ModuleConfig.TAG}")
            appendLine()
            appendLine("若日志为空，likely：")
            appendLine("  1. LSPosed 未激活本模块")
            appendLine("  2. 未勾选 android 作用域")
            appendLine("  3. 模块激活后尚未重启")
        }
    }

    private fun execToString(cmd: Array<String>): String? =
        runCatching {
            val proc = Runtime.getRuntime().exec(cmd)
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            out.ifBlank { null }
        }.getOrNull()

    // ── 复制 ─────────────────────────────────────────────────────────────────

    private fun copyLogs() {
        val text = tvLog.text?.toString()?.takeIf { it.isNotBlank() } ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(ModuleConfig.TAG, text))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }
}
