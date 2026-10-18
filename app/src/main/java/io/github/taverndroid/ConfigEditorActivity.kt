package io.github.taverndroid

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * 直接编辑 SillyTavern 的 config.yaml（多用户、代理、备份、缩略图等所有服务器选项）。
 * 端口、监听地址、数据目录、白名单、基础认证、SSL 由 app 在启动命令里决定，会盖过文件里的值。
 */
class ConfigEditorActivity : ComponentActivity() {
    private lateinit var editor: EditText
    private lateinit var config: File

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val paths = StPaths(this)
        config = paths.config
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x10, 0x10, 0x10))
        }
        val note = TextView(this).apply {
            text = "port、listen、dataRoot、whitelist、basicAuthMode、ssl、browserLaunch 由 App 设置决定，这里改了也不生效。"
            textSize = 12f
            setTextColor(Color.rgb(0x99, 0x99, 0x99))
            setPadding(24, 12, 24, 12)
        }
        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.rgb(0xe0, 0xe0, 0xe0))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            setText(if (config.exists()) config.readText() else "# 第一次启动酒馆后会生成 config.yaml\n")
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "保存并重启"
            isAllCaps = false
            setOnClickListener { save() }
        })
        buttons.addView(Button(this).apply {
            text = "恢复默认"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@ConfigEditorActivity)
                    .setMessage("用 SillyTavern 的默认配置替换编辑框里的内容？（点「保存并重启」才会真正写入）")
                    .setPositiveButton("替换") { _, _ ->
                        val default = File(paths.app, "default/config.yaml")
                        if (default.exists()) editor.setText(default.readText())
                    }
                    .setNegativeButton("取消", null).show()
            }
        })
        root.addView(buttons)
        root.addView(note)
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun save() {
        val backup = File(config.parentFile, "config.yaml.bak")
        if (config.exists()) config.copyTo(backup, overwrite = true)
        config.writeText(editor.text.toString())
        Toast.makeText(this, "已保存（旧版本留在 config.yaml.bak）", Toast.LENGTH_LONG).show()
        ServerService.restart(this)
        finish()
    }
}
