package fuck.andes

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

        findViewById<Button>(R.id.btn_test_google).setOnClickListener { testGoogleLaunch() }
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

    // ── 测试直接启动 Google ──────────────────────────────────────────────────

    private fun testGoogleLaunch() {
        val cts = Intent(ModuleConfig.CONTEXTUAL_SEARCH_ACTION)
            .setPackage(ModuleConfig.GOOGLE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val voice = Intent(Intent.ACTION_VOICE_COMMAND)
            .setPackage(ModuleConfig.GOOGLE_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        when {
            packageManager.resolveActivity(cts, 0) != null -> {
                startActivity(cts)
                Toast.makeText(this, "已通过 Circle to Search 入口启动", Toast.LENGTH_SHORT).show()
            }
            packageManager.resolveActivity(voice, 0) != null -> {
                startActivity(voice)
                Toast.makeText(this, "已通过 VOICE_COMMAND 启动 Google", Toast.LENGTH_SHORT).show()
            }
            else -> Toast.makeText(
                this,
                "✗ Google App 未安装或未暴露任何入口，模块 Hook 成功也无法启动 Google",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── 日志读取 ─────────────────────────────────────────────────────────────

    private fun loadLogs() {
        val btn = findViewById<Button>(R.id.btn_refresh)
        btn.isEnabled = false
        tvLog.text = "正在获取日志..."
        Thread {
            tryGrantReadLogs()   // 先通过 su 授权
            val text = fetchLogcat()
            runOnUiThread {
                tvLog.text = text
                scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
                btn.isEnabled = true
            }
        }.start()
    }

    /** READ_LOGS 是签名权限，需 root 授权一次。 */
    private fun tryGrantReadLogs() {
        runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "pm grant fuck.andes android.permission.READ_LOGS"))
                .waitFor()
        }
    }

    private fun fetchLogcat(): String {
        // 1. 直接读取（grant 成功后就能用）
        val direct = execToString(arrayOf(
            "logcat", "-d", "-t", "500", "-v", "threadtime", "-s", "${ModuleConfig.TAG}:V"
        ))
        if (!direct.isNullOrBlank()) return direct

        // 2. 通过 su 直接执行 logcat
        val viaSu = execToString(arrayOf(
            "su", "-c", "logcat -d -t 500 -v threadtime -s ${ModuleConfig.TAG}:V"
        ))
        if (!viaSu.isNullOrBlank()) return viaSu

        return buildString {
            appendLine("[无法读取 logcat]（su 授权未得到批准？）")
            appendLine()
            appendLine("手动授权后再刷新：")
            appendLine("  adb shell pm grant fuck.andes android.permission.READ_LOGS")
            appendLine()
            appendLine("或用 adb 直接看：")
            appendLine("  adb logcat -s ${ModuleConfig.TAG}")
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
