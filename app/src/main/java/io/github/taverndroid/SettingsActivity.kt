package io.github.taverndroid

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** App 自己的设置（SillyTavern 自己的设置仍然在它的界面里改）。 */
@Suppress("UseSwitchCompatOrMaterialCode")
class SettingsActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var stInfo: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var splashInfo: TextView
    private var bgRefresh: (() -> Unit)? = null
    private var busy: AlertDialog? = null

    private val pickImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport(uri)
    }
    private val pickSplash = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) applySplash(uri)
    }
    private val pickIcon = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) applyIcon(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        scroll.addView(list)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        build()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ServerController.state.collect { updateServerStatus(it) } }
                launch { StUpdater.state.collect { renderUpdate(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bgRefresh?.invoke()
    }

    override fun onDestroy() {
        hideBusy()
        super.onDestroy()
    }

    private fun build() {
        title("酒馆 App 设置")

        // ------------------------------------------------------------ 状态
        section("运行状态")
        status = text("", 14f, LIGHT).apply { setTextIsSelectable(true) }
        row(
            button("重启酒馆") { ServerService.restart(this) },
            button("查看日志") { startActivity(Intent(this, LogActivity::class.java)) },
            button("退出") {
                confirm("退出酒馆？", "会结束后台的酒馆程序和通知，跟在最近任务里划掉一样。") { ServerService.exit(this) }
            },
        )

        // ------------------------------------------------------------ 显示
        section("显示")
        scaleSetting()
        switch("收起状态栏", "隐藏顶部的时间、电量栏。从屏幕顶端往下滑可以临时叫出来。",
            { prefs.hideStatusBar }) { prefs.hideStatusBar = it }
        switch("收起导航条", "隐藏底部的导航条 / 手势条。从屏幕底部往上滑可以临时叫出来。",
            { prefs.hideNavigationBar }) { prefs.hideNavigationBar = it }
        switch("屏幕常亮", "停在聊天界面时不自动息屏。", { prefs.keepScreenOn }) { prefs.keepScreenOn = it }
        hint("刘海、圆角和键盘会自动让开；横屏跟随手机的自动旋转设置。")

        list.addView(button("一键「省电流畅」") { confirmPerfTweak() })
        hint("把酒馆的四项设置调成更省电、更流畅：No Blur Effect 开、Reduced Motion 开、Streaming FPS 12、# Messages to Load 30。不点就不会改。")

        // ------------------------------------------------------------ 开屏和图标
        section("开屏和图标")
        splashInfo = text("", 14f, LIGHT)
        showSplashInfo()
        row(
            button("换开屏图片") { pickSplash.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            button("恢复默认开屏") {
                Appearance.resetSplash(this)
                showSplashInfo()
                toast("下次打开酒馆时换回默认开屏")
            },
        )
        hint("自己选的图会铺满整个开屏，「Silly Tavern」和进度条压在下方，竖图最合适。下次打开酒馆时生效。")
        row(
            button("用一张图做桌面图标") {
                pickIcon.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            button("恢复默认图标") {
                Appearance.resetIcon(this)
                ServerService.refreshNotification(this)
                toast("通知里的图标换回默认的门了。桌面上自己建的那个快捷方式，长按它就能移除")
            },
        )
        hint("安卓不允许 app 换掉自己的图标，所以会在桌面上新建一个用这张图的「酒馆」快捷方式，点它一样打开酒馆；原来的图标可以收进文件夹。以后再选一张，会直接换掉这个快捷方式的图。点了没反应的话，到手机设置里给「酒馆」打开「桌面快捷方式」权限再试。")
        hint("通知左边和状态栏的小图标会换成从这张图抠出来的白色剪影（安卓规定那里只能是单色）。背景干净的图抠得最好，抠不出像样的图案就还用门的剪影。vivo 这类手机的通知左边画的是 app 本体的图标，app 自己改不了，那里会一直是默认的门。")

        // ------------------------------------------------------------ 后台
        section("后台运行")
        val bgStatus = text("", 14f, LIGHT)
        fun showBg() {
            bgStatus.text = if (Background.isUnrestricted(this)) "电池：不限制 ✓（切到别的 app 时回复也能继续生成）"
            else "电池：系统优化中。vivo 等手机切到后台后可能会暂停回复，建议点下面「允许不限制」。"
        }
        showBg()
        bgRefresh = ::showBg
        row(
            button("允许不限制") { Background.requestUnrestricted(this) },
            button("系统应用设置") { Background.openAppSettings(this) },
        )
        hint("vivo / iQOO 还要在 系统应用设置 → 电池 →「允许后台高耗电」里打开。详见 README。")
        switch("生成时保持 CPU 唤醒", "息屏时回复也能写完；回复结束就释放。",
            { prefs.wakeDuringGeneration }) { prefs.wakeDuringGeneration = it }
        switch("回复通知", "app 在后台时，角色回复了就发通知。", { prefs.notifyReplies }) { prefs.notifyReplies = it }
        switch("通知里显示回复内容", "关闭时通知只写「某某回复了你」。",
            { prefs.notifyReplyText }) { prefs.notifyReplyText = it }

        // ------------------------------------------------------------ 数据
        section("数据")
        list.addView(button("一键导出全部数据") { runExport() })
        hint("打包成 zip 存到 下载/${FileSaver.FOLDER}/。包含聊天、角色卡、世界书、预设、设置、secrets.json（API key）、插件和扩展。请妥善保管这个文件。")
        list.addView(button("导入数据包（zip）") {
            pickImport.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        })
        hint("合并覆盖：zip 里有的文件覆盖进来，没有的保留。导入前会先自动备份到 下载/${FileSaver.FOLDER}/${BackupManager.AUTO_FOLDER}/（只留最近 ${BackupManager.KEEP_AUTO} 个）。zip 格式见 README。")

        // ------------------------------------------------------------ 本体
        section("SillyTavern 本体")
        stInfo = text("", 14f, LIGHT).apply { setTextIsSelectable(true) }
        showStInfo()
        updateStatus = text("", 13f, MUTED_TEXT).apply { setTextIsSelectable(true) }
        updateButton = button("检查更新") { onUpdateButton() }
        list.addView(updateButton)
        val registryLabel = text("", 13f, MUTED_TEXT)
        fun showRegistry() {
            registryLabel.text = "装依赖用的 npm 源：" +
                if (prefs.npmRegistry == Prefs.NPM_MIRROR) "国内镜像（npmmirror）" else "官方（npmjs.org）"
        }
        showRegistry()
        row(
            button("用官方源") { prefs.npmRegistry = Prefs.NPM_OFFICIAL; showRegistry() },
            button("用国内镜像") { prefs.npmRegistry = Prefs.NPM_MIRROR; showRegistry() },
        )
        hint("升级会先自动备份，再从 GitHub 下载新版、在手机上装依赖。需要能连上 GitHub（开 VPN 即可）。程序和数据分开放，升级不会动你的数据。")
        row(
            button("恢复到安装包自带版本") {
                confirm("恢复到安装包自带的 SillyTavern？", "app 内升级过的程序会被换回 ${BuildConfig.ST_BUNDLE.substringBefore('-')}。数据不受影响。") {
                    resetProgram()
                }
            },
            button("修复程序文件") {
                confirm("修复程序文件？", "重新解压安装包里的 SillyTavern 程序文件（app 内升级过的话也会换回自带版本）。数据不受影响。") {
                    resetProgram()
                }
            },
        )

        // ------------------------------------------------------------ 高级
        section("高级")
        numberSetting("端口", "默认 ${Prefs.DEFAULT_PORT}。如果 Termux 里的酒馆也开着并占用了同一个端口，就换一个。",
            { prefs.port }, { value ->
                if (value !in 1024..65535) { toast("请填 1024～65535 之间的数字"); false }
                else { prefs.port = value; ServerService.restart(this); true }
            })
        switch("允许局域网里的其他设备访问",
            "默认关闭，只在本机 127.0.0.1 监听。打开后同一 Wi-Fi 下的电脑可以用下面的账号密码登录。",
            { prefs.lanAccess }) { prefs.lanAccess = it; ServerService.restart(this) }
        val creds = text("", 13f, MUTED_TEXT).apply { setTextIsSelectable(true) }
        fun showCreds() {
            creds.text = "用户名：${prefs.lanUser}\n密码：${prefs.lanPassword}"
        }
        showCreds()
        row(
            button("复制密码") { copy(prefs.lanPassword) },
            button("换个密码") {
                prefs.lanPassword = Prefs.randomPassword()
                showCreds()
                if (prefs.lanAccess) ServerService.restart(this)
            },
        )
        switch("启用服务端插件", "不管 config.yaml 里怎么写，都打开 server plugins。", { prefs.serverPlugins }) {
            prefs.serverPlugins = it
            ServerService.restart(this)
        }
        numberSetting("Node.js 内存上限（MB）", "0 = 自动。聊天很长或用向量存储时服务器崩溃，可以调大。",
            { prefs.nodeHeapMb }, { value ->
                if (value != 0 && value !in 256..16384) { toast("请填 0 或 256～16384"); false }
                else { prefs.nodeHeapMb = value; ServerService.restart(this); true }
            })
        switch("网页调试", "电脑 Chrome 打开 chrome://inspect 可以调试酒馆页面（排查问题时用）。",
            { prefs.webDebugging }) { prefs.webDebugging = it }
        list.addView(button("编辑 config.yaml") { startActivity(Intent(this, ConfigEditorActivity::class.java)) })
        list.addView(button("清除前端编译缓存") {
            confirm("清除前端编译缓存？", "酒馆会重启，下次打开稍慢一点。界面显示异常时可以试试。") {
                val app = applicationContext
                lifecycleScope.launch {
                    try {
                        ServerController.stop()
                        withContext(Dispatchers.IO) { Fs.deleteTree(File(StPaths(app).data, "_webpack")) }
                    } finally {
                        withContext(NonCancellable) { ServerController.start(app) }
                    }
                }
            }
        })

        // ------------------------------------------------------------ 关于
        section("关于")
        val paths = StPaths(this)
        text(
            "酒馆 ${BuildConfig.VERSION_NAME}\n" +
                "安装包自带 SillyTavern ${BuildConfig.ST_BUNDLE}\n" +
                "Node.js 与 git：Termux 编译版\n" +
                "数据目录：${paths.data.absolutePath}\n" +
                "日志：${paths.serverLog.absolutePath}",
            13f, MUTED_TEXT
        ).setTextIsSelectable(true)
        hint(getString(R.string.license_notice))
        list.addView(button("开源许可") { startActivity(Intent(this, LicensesActivity::class.java)) })
        list.addView(button("源代码和新版本（GitHub）") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_url))))
        })
    }

    // ------------------------------------------------------------ 缩放

    private fun scaleSetting() {
        val label = text("", 16f, Color.WHITE).apply { setPadding(0, dp(10), 0, 0) }
        fun show(value: Int) {
            label.text = "整体缩放：$value%"
        }
        show(prefs.uiScale)
        val steps = (Prefs.MAX_SCALE - Prefs.MIN_SCALE) / SCALE_STEP
        val bar = SeekBar(this).apply {
            max = steps
            progress = (prefs.uiScale - Prefs.MIN_SCALE) / SCALE_STEP
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val value = Prefs.MIN_SCALE + progress * SCALE_STEP
                    show(value)
                    if (fromUser) prefs.uiScale = value
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        list.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        row(
            button("−") { bar.progress = (bar.progress - 1).coerceAtLeast(0); prefs.uiScale = Prefs.MIN_SCALE + bar.progress * SCALE_STEP },
            button("恢复 100%") { bar.progress = (100 - Prefs.MIN_SCALE) / SCALE_STEP; prefs.uiScale = 100 },
            button("＋") { bar.progress = (bar.progress + 1).coerceAtMost(steps); prefs.uiScale = Prefs.MIN_SCALE + bar.progress * SCALE_STEP },
        )
        hint("${Prefs.MIN_SCALE}%～${Prefs.MAX_SCALE}%，字、按钮、图标一起放大缩小。返回聊天界面立刻生效，会一直记住。")
    }

    // ------------------------------------------------------------ 省电流畅

    private fun confirmPerfTweak() {
        AlertDialog.Builder(this)
            .setTitle("开启「省电流畅」？")
            .setMessage(
                "会在酒馆的「用户设置」里改这四项（只改当前登录的用户）：\n\n" +
                    "• No Blur Effect：开\n• Reduced Motion：开\n• Streaming FPS：12\n• # Messages to Load：30\n\n" +
                    "回到聊天界面时自动生效。以后想改回来，直接在酒馆的用户设置里改就行。"
            )
            .setPositiveButton("开启") { _, _ ->
                prefs.pendingPerfTweak = true
                toast("回到聊天界面时生效")
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 导出 / 导入

    private fun runExport() {
        showBusy("正在打包数据…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    BackupManager(this@SettingsActivity).backupToDownloads("酒馆数据", auto = false) { msg ->
                        runOnUiThread { busy?.setMessage(msg) }
                    }
                }
            }
            hideBusy()
            result.onSuccess { where ->
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("导出完成")
                    .setMessage("已保存到：\n$where\n\n里面有你的 API key（secrets.json），请不要随便发给别人。")
                    .setPositiveButton("好", null)
                    .show()
            }.onFailure { toast("导出失败：${it.message}") }
        }
    }

    private fun confirmImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("导入这个数据包？")
            .setMessage(
                "步骤：先把 app 里现有的数据自动备份到 下载/${FileSaver.FOLDER}/${BackupManager.AUTO_FOLDER}/ → 暂停酒馆 → " +
                    "把 zip 里的文件合并覆盖进来（zip 里没有的保留）→ 重新打开酒馆。\n\n数据多的话要等一会儿，请不要退出 app。"
            )
            .setPositiveButton("开始导入") { _, _ -> runImport(uri) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runImport(uri: Uri) {
        showBusy("正在准备…")
        lifecycleScope.launch {
            // 先让聊天页面离开，免得旧页面在导入后把旧设置写回去
            PageControl.unload()
            delay(1200)
            val manager = BackupManager(this@SettingsActivity)
            val update: (String) -> Unit = { msg -> runOnUiThread { busy?.setMessage(msg) } }
            val backup = withContext(Dispatchers.IO) {
                runCatching { manager.backupToDownloads("导入前", auto = true) { update("正在备份现有数据：$it") } }
            }
            if (backup.isFailure) {
                hideBusy()
                ServerController.start(this@SettingsActivity)
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("没有导入")
                    .setMessage("导入前的自动备份失败了（${backup.exceptionOrNull()?.message}），为了安全没有继续导入。现有数据没有变化。")
                    .setPositiveButton("好", null).show()
                return@launch
            }
            update("正在暂停酒馆…")
            val app = applicationContext
            val result = try {
                ServerController.stop()
                withContext(Dispatchers.IO) { runCatching { manager.restore(uri, update) } }
            } finally {
                // 不管成功失败（哪怕这个界面被关掉了），都要把酒馆重新打开
                withContext(NonCancellable) { ServerController.start(app) }
            }
            hideBusy()
            result.onSuccess { r ->
                val failed = if (r.failed > 0) "\n有 ${r.failed} 个文件没能写入，详情可以看日志。" else ""
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("导入完成")
                    .setMessage(
                        "导入了 ${r.files} 个文件。$failed\n导入前的数据备份在：\n${backup.getOrNull()}\n\n" +
                            "要不要顺便开启「省电流畅」？（No Blur Effect 开、Reduced Motion 开、Streaming FPS 12、# Messages to Load 30）"
                    )
                    .setPositiveButton("开启省电流畅") { _, _ ->
                        prefs.pendingPerfTweak = true
                        finish()
                    }
                    .setNegativeButton("不用了") { _, _ -> finish() }
                    .show()
            }.onFailure {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("导入失败")
                    .setMessage("${it.message}\n\n导入前的数据已经备份在：\n${backup.getOrNull()}\n如果数据乱了，可以再导入这个备份恢复。")
                    .setPositiveButton("好", null).show()
            }
        }
    }

    // ------------------------------------------------------------ 升级

    private fun showStInfo() {
        val installer = Installer(this, StPaths(this))
        val version = installer.installedStVersion() ?: "（还没解压，第一次打开后显示）"
        val source = if (installer.isUpgraded()) "app 内升级" else "安装包自带"
        stInfo.text = "当前版本：$version（$source）"
    }

    private fun onUpdateButton() {
        when (val s = StUpdater.state.value) {
            is StUpdater.State.Available -> confirmUpgrade(s)
            is StUpdater.State.Working, StUpdater.State.Checking -> Unit
            else -> StUpdater.checkForUpdate(this)
        }
    }

    private fun confirmUpgrade(s: StUpdater.State.Available) {
        val notes = s.release.notes.take(1500).ifBlank { "（没有更新说明）" }
        AlertDialog.Builder(this)
            .setTitle("升级到 SillyTavern ${s.release.version}？")
            .setMessage(
                "当前 ${s.installed} → ${s.release.version}\n\n" +
                    "会先把数据自动备份到 下载/${FileSaver.FOLDER}/${BackupManager.AUTO_FOLDER}/，再下载、装依赖，最后自动重启酒馆。" +
                    "过程中可以继续聊天，最后切换时会停几秒。\n\n更新说明：\n$notes"
            )
            .setPositiveButton("升级") { _, _ -> StUpdater.upgrade(this, s.release) }
            .setNeutralButton("网页") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.release.page))) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderUpdate(s: StUpdater.State) {
        when (s) {
            StUpdater.State.Idle -> {
                updateStatus.text = ""
                updateButton.text = "检查更新"
                updateButton.isEnabled = true
            }
            StUpdater.State.Checking -> {
                updateStatus.text = "正在查询 GitHub 上的最新版本…"
                updateButton.isEnabled = false
            }
            is StUpdater.State.UpToDate -> {
                updateStatus.text = "已经是最新版（GitHub 最新正式版 ${s.latest}）。"
                updateButton.text = "再检查一次"
                updateButton.isEnabled = true
            }
            is StUpdater.State.Available -> {
                updateStatus.text = "有新版本：${s.release.version}（当前 ${s.installed}）"
                updateButton.text = "升级到 ${s.release.version}"
                updateButton.isEnabled = true
            }
            is StUpdater.State.Working -> {
                val percent = s.progress?.let { " ${(it * 100).toInt()}%" } ?: ""
                updateStatus.text = s.message + percent
                updateButton.text = "升级中…"
                updateButton.isEnabled = false
            }
            is StUpdater.State.Done -> {
                updateStatus.text = "已升级到 ${s.version}，酒馆正在重启。升级前的备份：${s.backup}"
                updateButton.text = "检查更新"
                updateButton.isEnabled = true
                showStInfo()
            }
            is StUpdater.State.Failed -> {
                updateStatus.text = s.message
                updateButton.text = "重新检查"
                updateButton.isEnabled = true
            }
        }
    }

    private fun resetProgram() {
        if (StUpdater.isBusy) {
            toast("正在升级 SillyTavern，等它完成后再试")
            return
        }
        val app = applicationContext
        lifecycleScope.launch {
            try {
                ServerController.stop()
                Installer(app, StPaths(app)).invalidate()
                StUpdater.reset()
            } finally {
                withContext(NonCancellable) { ServerController.start(app) }
            }
            toast("正在重新解压，稍等片刻")
            delay(500)
            showStInfo()
        }
    }

    // ------------------------------------------------------------ 状态

    private fun updateServerStatus(state: ServerController.State) {
        val base = when (state) {
            is ServerController.State.Running -> "营业中：${state.url}"
            is ServerController.State.Preparing -> state.message
            ServerController.State.Starting -> "正在启动…"
            is ServerController.State.Failed -> "出错停止了：${state.message}"
            ServerController.State.Stopped -> "已停止"
        }
        val lan = if (prefs.lanAccess && state is ServerController.State.Running) {
            val urls = Network.lanUrls(prefs.port)
            if (urls.isEmpty()) "\n局域网：没找到 Wi-Fi 地址" else "\n局域网：" + urls.joinToString("\n　　　　")
        } else ""
        status.text = base + lan
        if (state is ServerController.State.Running) showStInfo()
    }

    // ------------------------------------------------------------ 小工具

    // ------------------------------------------------------------ 开屏和图标

    private fun showSplashInfo() {
        splashInfo.text = if (Appearance.customSplash(this) != null) "开屏：自己选的图" else "开屏：默认图"
    }

    private fun applySplash(uri: Uri) {
        showBusy("正在处理图片…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Appearance.saveSplash(this@SettingsActivity, uri) } }
            hideBusy()
            result.onSuccess {
                showSplashInfo()
                toast("换好了，下次打开酒馆时就是这张")
            }.onFailure { toast("这张图用不了：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun applyIcon(uri: Uri) {
        showBusy("正在处理图片…")
        lifecycleScope.launch {
            val art = withContext(Dispatchers.IO) { runCatching { Appearance.saveIcon(this@SettingsActivity, uri) } }
            hideBusy()
            val bitmap = art.getOrElse {
                toast("这张图用不了：${it.message ?: it.javaClass.simpleName}")
                return@launch
            }
            ServerService.refreshNotification(this@SettingsActivity) // 常驻通知马上换成新图标
            val result = runCatching { Appearance.pinIcon(this@SettingsActivity, bitmap) }.getOrDefault(Appearance.PinResult.FAILED)
            toast(
                when (result) {
                    Appearance.PinResult.REQUESTED -> "桌面会弹出添加提示，点「添加」就好"
                    Appearance.PinResult.UPDATED -> "桌面上的快捷图标已经换成这张"
                    Appearance.PinResult.UNSUPPORTED -> "这个桌面不支持 app 添加快捷方式"
                    Appearance.PinResult.FAILED -> "没能添加。到手机设置里给「酒馆」打开「桌面快捷方式」权限再试"
                }
            )
        }
    }

    private fun showBusy(message: String) {
        hideBusy()
        busy = AlertDialog.Builder(this).setMessage(message).setCancelable(false).show()
    }

    private fun hideBusy() {
        busy?.dismiss()
        busy = null
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("确定") { _, _ -> action() }
            .setNegativeButton("取消", null).show()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("password", text))
        toast("已复制")
    }

    private fun title(t: String) = text(t, 24f, Color.WHITE).apply { setPadding(0, dp(8), 0, dp(8)) }

    private fun section(t: String) = text(t, 14f, ACCENT).apply {
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(26), 0, dp(6))
    }

    private fun hint(t: String) = text(t, 12f, HINT).apply { setPadding(0, dp(2), 0, dp(10)) }

    private fun text(t: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        list.addView(this)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        list.addView(row)
    }

    private fun switch(label: String, description: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = get()
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }
        list.addView(sw)
        hint(description)
    }

    private fun numberSetting(label: String, description: String, get: () -> Int, set: (Int) -> Boolean) {
        text(label, 16f, Color.WHITE).setPadding(0, dp(10), 0, 0)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(get().toString())
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        val apply = button("应用") {
            val value = input.text.toString().toIntOrNull()
            if (value == null || !set(value)) input.setText(get().toString())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(apply)
        list.addView(row)
        hint(description)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCALE_STEP = 5
        private val BG = Color.rgb(0x15, 0x11, 0x0e)
        private val ACCENT = Color.rgb(0xd9, 0xa5, 0x5b)
        private val LIGHT = Color.rgb(0xe6, 0xdc, 0xcc)
        private val MUTED_TEXT = Color.rgb(0xb0, 0xa6, 0x96)
        private val HINT = Color.rgb(0x8f, 0x86, 0x78)
    }
}
