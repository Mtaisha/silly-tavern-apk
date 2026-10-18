package io.github.taverndroid

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 显示随 app 一起打包的第三方软件的许可证（由 tools/prepare.sh 生成）。 */
class LicensesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.rgb(0xd0, 0xd0, 0xd0))
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
            text = getString(R.string.license_notice) + "\n\n" +
                runCatching { assets.open("THIRD_PARTY_NOTICES.txt").use { it.readBytes().decodeToString() } }
                    .getOrDefault("这个安装包里没有 THIRD_PARTY_NOTICES.txt。") +
                // 开屏花体字的许可（随源码放在 assets/licenses/，不是 prepare.sh 生成的）
                runCatching { assets.open("licenses/fonts.txt").use { "\n\n" + "=".repeat(80) + "\n" + it.readBytes().decodeToString() } }
                    .getOrDefault("")
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0x10, 0x10, 0x10))
            addView(text)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
