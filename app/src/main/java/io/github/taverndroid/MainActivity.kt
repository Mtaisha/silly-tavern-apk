package io.github.taverndroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 全屏 WebView，显示 app 自带 Node.js 跑起来的 SillyTavern 界面。 */
class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var container: FrameLayout
    private lateinit var splashBackdrop: ImageView
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var splashTop: View
    private lateinit var splashArt: ImageView
    private lateinit var overlayTitle: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var overlayProgress: SplashBar
    private lateinit var overlayButtons: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var overlayLog: TextView
    private lateinit var splashBottom: View
    private var customSplash = false
    private var splashStamp = -1L
    private var overlayDone = false
    private var fittedFor = ""
    private var startPending = false
    private lateinit var fileSaver: FileSaver

    private var loadedUrl: String? = null
    private var loadedRun = -1
    private var pageShown = false
    private var resumed = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingLegacySave: FileSaver.Pending? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var nativeTts: NativeTts? = null
    private var cameraFile: File? = null
    private var perfTweakRunning = false
    private val bridgeScript by lazy { assets.open("taverndroid.js").use { it.readBytes().decodeToString() } }

    /**
     * 盖在自定义开屏图上的渐暗：顶上淡淡压一点（状态栏的白字看得清），中间透明，
     * 往下越来越暗（压在上面的花体字、进度条和导航条看得清）。
     */
    private val splashScrim by lazy {
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(0x66, 0x01, 0x10, 0x25), Color.TRANSPARENT, Color.TRANSPARENT,
                Color.argb(0x8c, 0x01, 0x10, 0x25), Color.argb(0xe6, 0x01, 0x10, 0x25),
            ),
        )
    }

    /** 出错、打烊时要看说明文字和按钮，自定义开屏图整屏均匀压暗。 */
    private val splashDim by lazy { ColorDrawable(Color.argb(0xcc, 0x01, 0x10, 0x25)) }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        val shot = cameraFile
        cameraFile = null
        val data = result.data
        val clip = data?.clipData
        val single = data?.data
        val uris: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> null
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            single != null -> arrayOf(single)
            // 选了「拍照」：相机把照片写进了我们给它的文件
            shot != null && shot.length() > 0 -> arrayOf(FileProvider.getUriForFile(this, "$packageName.files", shot))
            else -> null
        }
        callback.onReceiveValue(uris)
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val request = pendingPermission ?: return@registerForActivityResult
        pendingPermission = null
        val allowed = request.resources.filter { res ->
            res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && (granted[Manifest.permission.RECORD_AUDIO] == true ||
                hasPermission(Manifest.permission.RECORD_AUDIO))
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val legacySave = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingLegacySave ?: return@registerForActivityResult
        pendingLegacySave = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                fileSaver.writeTo(pending, uri)
                withContext(Dispatchers.Main) { toast("已保存 ${pending.name}") }
            }
        } else {
            pending.file.delete()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        fileSaver = FileSaver(this)
        buildViews()
        configureWebView()
        applySystemBars()
        onBackPressedDispatcher.addCallback(this, backCallback)

        startServer()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ServerController.state.collect { render(it) } }
                launch {
                    ServerController.log.version.collect {
                        if (overlay.visibility == View.VISIBLE) {
                            overlayLog.text = ServerController.log.tail(14).joinToString("\n")
                        }
                    }
                }
            }
        }
        // 导入数据前，设置页会让这里先离开聊天页面（Activity 在后台时也要执行，所以不跟生命周期走）
        lifecycleScope.launch {
            var seen = PageControl.unloads.value
            PageControl.unloads.collect { n ->
                if (n != seen) {
                    seen = n
                    unloadPage()
                }
            }
        }
        handleIntent(intent)
    }

    /** 离开聊天页面（换成空白页）。服务器下次就绪时会重新打开。 */
    private fun unloadPage() {
        perfTweakRunning = false
        loadedUrl = null
        loadedRun = -1
        pageShown = false
        webView.loadUrl("about:blank")
        showOverlay("正在处理数据…", null)
    }

    /** 用户点了「省电流畅」：页面已经打开就去改那四项设置，没打开就等页面就绪时再改。 */
    private fun applyPendingPerfTweak() {
        if (!prefs.pendingPerfTweak || !pageShown || isDestroyed || perfTweakRunning) return
        perfTweakRunning = true
        webView.evaluateJavascript("window.__jiuguanPerf ? window.__jiuguanPerf() : 'not-ready'") { result ->
            perfTweakRunning = false
            val text = result?.trim('"') ?: ""
            if (text.startsWith("ok")) {
                prefs.pendingPerfTweak = false
                toast("已开启省电流畅：No Blur Effect、Reduced Motion、Streaming FPS 12、# Messages to Load 30")
            } else if (text != "not-ready") {
                prefs.pendingPerfTweak = false
                toast("省电流畅没有改成功：$text")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Launcher shortcuts (res/xml/shortcuts.xml) come through here so the other screens stay unexported.
        when (intent?.getStringExtra(EXTRA_OPEN)) {
            "settings" -> openSettings()
            "log" -> startActivity(Intent(this, LogActivity::class.java))
        }
        intent?.removeExtra(EXTRA_OPEN)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyWindowFlags()
        applySystemBars()
        // 在 App 设置里改了缩放比例，回来时立刻生效
        if (pageShown) webView.evaluateJavascript("window.__jiuguanApplyScale && window.__jiuguanApplyScale()", null)
        applyPendingPerfTweak()
        // Some vendor freezers (vivo/iQOO, OPPO) thaw the app's main process on return but leave the WebView
        // renderer frozen until it gets input, so a reply that paused in the background would only continue
        // after a tap. Talking to the renderer right away (resume + a no-op script) thaws it.
        webView.onResume()
        webView.evaluateJavascript("void 0", null)
        webView.postDelayed({ if (!isDestroyed) webView.evaluateJavascript("void 0", null) }, 500)
        WebView.setWebContentsDebuggingEnabled(prefs.webDebugging || BuildConfig.DEBUG)
        NotificationManagerCompat.from(this).cancel(REPLY_NOTIFICATION_ID)
        maybeAskNotificationPermission()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 弹窗、下拉通知栏之后系统栏可能被放出来，重新收起
        if (hasFocus) applySystemBars()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    @Synchronized
    private fun speech(): NativeTts = nativeTts ?: NativeTts(this) { js ->
        runOnUiThread { if (!isDestroyed) webView.evaluateJavascript(js, null) }
    }.also { nativeTts = it }

    override fun onDestroy() {
        container.removeCallbacks(startTimeout)
        nativeTts?.shutdown()
        root.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ views

    private fun buildViews() {
        // 最外层：开屏时是开屏底色（或者铺满整屏的自定义开屏图，连状态栏底下也铺），页面打开后换回原来的底色
        container = FrameLayout(this).apply { setBackgroundColor(SPLASH_BG) }
        splashBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
            visibility = View.GONE
        }
        container.addView(splashBackdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root = FrameLayout(this)
        container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = WebView(this).apply {
            setBackgroundColor(PAGE_BG)
            visibility = View.INVISIBLE
        }
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 开屏：开屏图 + 花体「Silly Tavern」+ 细进度条。出错、打烊时才露出说明文字、按钮和启动日志。
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(12), 0, dp(20))
        }
        splashTop = View(this)
        splashArt = ImageView(this).apply {
            setImageResource(R.drawable.splash_art)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
        }
        overlayTitle = TextView(this).apply {
            text = getString(R.string.splash_title)
            typeface = resources.getFont(R.font.pinyon_script)
            setTextColor(SplashBar.CREAM)
            // 一圈淡淡的粉色光晕；四周留了内边距，光晕不会被裁掉
            setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.argb(0x70, 0xee, 0x71, 0x94))
            setPadding(dp(16), dp(4), dp(16), dp(4))
            gravity = Gravity.CENTER
        }
        overlayStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(6), dp(28), dp(12))
        }
        overlayProgress = SplashBar(this)
        overlayButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        overlayLog = TextView(this).apply {
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(0x7d, 0x86, 0x9c))
            setTextIsSelectable(true)
            setPadding(dp(28), 0, dp(28), 0)
        }
        logScroll = ScrollView(this).apply { addView(overlayLog) }
        splashBottom = View(this)
        overlay.addView(splashTop, LinearLayout.LayoutParams(1, 0, 1f))
        overlay.addView(splashArt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay.addView(overlayTitle)
        overlay.addView(overlayStatus)
        overlay.addView(overlayProgress, LinearLayout.LayoutParams(0, dp(3)).apply { topMargin = dp(14) })
        overlay.addView(overlayButtons)
        overlay.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.6f))
        overlay.addView(splashBottom, LinearLayout.LayoutParams(1, 0, 1f))
        // 每次画之前看一眼：屏幕尺寸（转屏、分屏）或版式变了，就先算好花体字多大、开屏图能占多高，
        // 取消这一帧、重新排好再画，第一帧也不会先闪一下没算好的样子
        overlay.viewTreeObserver.addOnPreDrawListener {
            if (overlay.visibility != View.VISIBLE || overlay.width == 0) return@addOnPreDrawListener true
            val key = "${overlay.width}x${overlay.height}/$customSplash/$overlayDone"
            if (key == fittedFor) return@addOnPreDrawListener true
            fittedFor = key
            fitSplash()
            false
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 一开始就按有没有自定义开屏图排好、开始读图（酒馆已经在跑、只是界面重建时不会再走 showOverlay）
        refreshSplash()

        setContentView(container)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            if (customView != null) {
                v.setPadding(0, 0, 0, 0)
                return@setOnApplyWindowInsetsListener WindowInsetsCompat.CONSUMED
            }
            // 系统栏收起时 getInsets 返回 0；刘海（cutout）始终要让开；圆角屏额外让出一点，
            // 键盘弹出时底部让出键盘高度，界面跟着收，输入框不会被挡住。
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val (cornerTop, cornerBottom) = cornerPadding(insets)
            v.setPadding(
                maxOf(status.left, nav.left, cutout.left),
                maxOf(status.top, cutout.top, cornerTop),
                maxOf(status.right, nav.right, cutout.right),
                maxOf(nav.bottom, cutout.bottom, ime.bottom, cornerBottom),
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /** 圆角屏：系统栏收起后，上下各让出圆角会裁掉的那一截（半径 × (1 − √2/2)）。 */
    private fun cornerPadding(insets: WindowInsetsCompat): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT < 31) return 0 to 0
        val platform = insets.toWindowInsets() ?: return 0 to 0
        fun cut(position: Int): Int {
            val corner = platform.getRoundedCorner(position) ?: return 0
            return (corner.radius * 0.2929f).toInt()
        }
        val top = maxOf(cut(RoundedCorner.POSITION_TOP_LEFT), cut(RoundedCorner.POSITION_TOP_RIGHT))
        val bottom = maxOf(cut(RoundedCorner.POSITION_BOTTOM_LEFT), cut(RoundedCorner.POSITION_BOTTOM_RIGHT))
        return top to bottom
    }

    /** 按设置收起/显示状态栏和导航条；收起后从屏幕边缘滑一下可以临时叫出来。 */
    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (prefs.hideStatusBar) controller.hide(WindowInsetsCompat.Type.statusBars())
        else controller.show(WindowInsetsCompat.Type.statusBars())
        if (prefs.hideNavigationBar) controller.hide(WindowInsetsCompat.Type.navigationBars())
        else controller.show(WindowInsetsCompat.Type.navigationBars())
        ViewCompat.requestApplyInsets(root)
    }

    private fun setButtons(vararg buttons: Pair<String, () -> Unit>) {
        overlayButtons.removeAllViews()
        for ((label, action) in buttons) {
            overlayButtons.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 15f
                setTextColor(SplashBar.CREAM)
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setStroke(dp(1), Color.argb(0x99, 0xf6, 0xd2, 0xc5))
                    setColor(Color.TRANSPARENT)
                }
                minHeight = dp(40)
                minimumHeight = dp(40)
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
        }
    }

    /**
     * 发出启动指令后，后台要过一小会儿才报「准备中 / 启动中」，这之前状态还是「打烊」。
     * 这段时间开屏按「正在开门」显示，免得先闪一下打烊画面；8 秒还没动静才显示真实状态。
     */
    private fun startServer(restart: Boolean = false) {
        startPending = true
        container.removeCallbacks(startTimeout)
        container.postDelayed(startTimeout, START_GRACE_MS)
        if (restart) ServerService.restart(this) else ServerService.start(this)
    }

    private val startTimeout = Runnable {
        if (startPending) {
            startPending = false
            render(ServerController.state.value)
        }
    }

    private fun render(state: ServerController.State) {
        if (state != ServerController.State.Stopped && startPending) {
            startPending = false
            container.removeCallbacks(startTimeout)
        }
        when (state) {
            is ServerController.State.Running -> {
                if (loadedUrl != state.url || loadedRun != state.run) {
                    loadedUrl = state.url
                    loadedRun = state.run
                    pageShown = false
                    perfTweakRunning = false
                    webView.loadUrl(state.url)
                }
                overlayStatus.text = "正在打开界面…"
                setButtons()
            }
            is ServerController.State.Preparing -> showOverlay(state.message, state.progress)
            ServerController.State.Starting -> showOverlay("酒馆正在开门…", null)
            is ServerController.State.Failed -> {
                showOverlay(state.message, null, done = true)
                setButtons(
                    "重试" to { startServer(restart = true) },
                    "日志" to { startActivity(Intent(this, LogActivity::class.java)) },
                    "设置" to { openSettings() },
                )
            }
            ServerController.State.Stopped -> if (startPending) {
                showOverlay("酒馆正在开门…", null)
            } else {
                showOverlay("酒馆已打烊。", null, done = true)
                setButtons(
                    "开门" to { startServer() },
                    "设置" to { openSettings() },
                )
            }
        }
    }

    private fun showOverlay(status: String, progress: Float?, done: Boolean = false) {
        pageShown = false
        loadedUrl = null
        refreshSplash()
        if (splashArt.drawable == null) splashArt.setImageResource(R.drawable.splash_art)
        container.setBackgroundColor(SPLASH_BG)
        splashBackdrop.visibility = if (customSplash) View.VISIBLE else View.GONE
        overlay.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        overlayStatus.text = status
        overlayProgress.progress = progress
        if (overlayDone != done) {
            overlayDone = done
            layoutSplash()
        }
        if (!done) setButtons()
        overlayLog.text = ServerController.log.tail(14).joinToString("\n")
    }

    /**
     * 平时：开屏图、花体字、进度条排成一组居中；用自定义开屏图时图铺满整屏，只有字和进度条压在下方。
     * 出错、打烊时：花体字、说明文字、按钮，下面是启动日志。
     */
    private fun layoutSplash() {
        val done = overlayDone
        splashArt.visibility = if (!done && !customSplash) View.VISIBLE else View.GONE
        overlayStatus.visibility = if (done) View.VISIBLE else View.GONE
        overlayProgress.visibility = if (done) View.GONE else View.VISIBLE
        overlayButtons.visibility = if (done) View.VISIBLE else View.GONE
        logScroll.visibility = if (done) View.VISIBLE else View.GONE
        (splashTop.layoutParams as LinearLayout.LayoutParams).weight = if (done) 0.35f else 1f
        (splashBottom.layoutParams as LinearLayout.LayoutParams).apply {
            when {
                done -> { weight = 0f; height = 0 }
                customSplash -> { weight = 0f; height = dp(40) }
                else -> { weight = 1f; height = 0 }
            }
        }
        // 压暗盖在整屏的图上（导航条底下也压到）；出错、打烊要看字的时候整屏均匀压暗
        splashBackdrop.foreground = when {
            !customSplash -> null
            done -> splashDim
            else -> splashScrim
        }
        overlay.requestLayout() // 下一次画之前 OnPreDrawListener 会按新版式重新算尺寸
    }

    /** 花体字约占屏幕宽的六成（宽屏封顶）；开屏图在字和进度条以外的高度里尽量大。 */
    private fun fitSplash() {
        val width = overlay.width
        val height = overlay.height - overlay.paddingTop - overlay.paddingBottom
        if (width <= 0 || height <= 0) return
        val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = overlayTitle.typeface
            textSize = 100f
        }
        val per100 = probe.measureText(overlayTitle.text.toString()).coerceAtLeast(1f)
        val byWidth = 100f * minOf(width * 0.58f, dp(320).toFloat()) / per100
        // 又矮又宽的窗口按高度封顶：Pinyon Script 的行高约是字号的 1.52 倍，字最多占可用高度的两成
        val byHeight = height * 0.2f / 1.52f
        overlayTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(byWidth, byHeight))
        (overlayProgress.layoutParams as LinearLayout.LayoutParams).width = minOf((width * 0.34f).toInt(), dp(200))
        overlayProgress.requestLayout()
        if (splashArt.visibility == View.VISIBLE) {
            overlayTitle.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val below = overlayTitle.measuredHeight + dp(3) + dp(14) // 花体字 + 进度条 + 间距
            splashArt.maxHeight = maxOf(0, height - below)
            splashArt.requestLayout()
        }
    }

    /** 设置里换了开屏图的话，下次显示开屏时用新的。 */
    private fun refreshSplash() {
        val file = Appearance.customSplash(this)
        val stamp = file?.lastModified() ?: 0L
        if (stamp == splashStamp) return
        splashStamp = stamp
        if (file == null) {
            splashBackdrop.setImageDrawable(null)
            setCustomSplash(false)
            return
        }
        setCustomSplash(true) // 图还在读的时候先只显示字和进度条，不闪默认图
        val metrics = resources.displayMetrics
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { Appearance.loadSplash(file, metrics.widthPixels, metrics.heightPixels) }.getOrNull()
            }
            if (splashStamp != stamp) return@launch // 读图期间又换了一张
            splashBackdrop.setImageBitmap(bitmap)
            if (bitmap == null) setCustomSplash(false)
        }
    }

    private fun setCustomSplash(custom: Boolean) {
        customSplash = custom
        splashBackdrop.visibility = if (custom && overlay.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        layoutSplash()
    }

    /**
     * By default WebView lowers its renderer process's priority when the app is not visible, which lets
     * freezers pause the page mid-reply. While a reply is generating, keep it bound as important.
     */
    private fun keepRendererImportant(important: Boolean) {
        if (Build.VERSION.SDK_INT < 26 || isDestroyed) return
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, !important)
    }

    private fun revealPage() {
        if (pageShown) return
        pageShown = true
        webView.visibility = View.VISIBLE
        overlay.visibility = View.GONE
        // 页面出来以后开屏图就用不着了，放掉省内存；下次显示开屏时会重新读
        splashBackdrop.visibility = View.GONE
        splashBackdrop.setImageDrawable(null)
        splashArt.setImageDrawable(null)
        splashStamp = -1L
        container.setBackgroundColor(BG)
    }

    private fun applyWindowFlags() {
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(prefs.webDebugging || BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // 提前把屏幕外的内容也栅格化，滚动长聊天更顺
            offscreenPreRaster = true
            userAgentString = "$userAgentString TavernDroid/${BuildConfig.VERSION_NAME} Jiuguan"
        }
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.addJavascriptInterface(Bridge(), "TavernDroidNative")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, bridgeScript, setOf("*"))
        }
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                webView.evaluateJavascript("window.__taverndroidSave && window.__taverndroidSave(${jsString(url)})", null)
            } else {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                downloadHttp(url, userAgent, name, mimeType)
            }
        }
    }

    private fun isLocal(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return (host == "127.0.0.1" || host == "localhost") && uri.port == prefs.port
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            toast("没有能打开 $uri 的应用")
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when {
                isLocal(uri) -> false
                uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto" || uri.scheme == "intent" -> {
                    openExternal(uri); true
                }
                uri.scheme == "about" || uri.scheme == "blob" || uri.scheme == "data" || uri.scheme == "javascript" -> false
                else -> { openExternal(uri); true }
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(bridgeScript, null)
            }
            if (isLocal(Uri.parse(url))) revealPage()
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            if (prefs.lanAccess) handler.proceed(prefs.lanUser, prefs.lanPassword) else handler.cancel()
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // The renderer crashed or was killed for memory; rebuild the activity instead of dying with it.
            Log.w(TAG, "WebView renderer gone (crash=${detail.didCrash()})")
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val mimes = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                .map { type ->
                    if (type.startsWith(".")) {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1).lowercase())
                    } else {
                        type
                    }
                }
            val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            // Only narrow the picker when every accepted type is a known MIME type
            // (SillyTavern also accepts .jsonl, .charx, .byaf, ... which Android cannot map).
            if (mimes.isNotEmpty() && mimes.all { it != null }) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.filterNotNull().distinct().toTypedArray())
            }
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            val chooser = Intent.createChooser(intent, "选择文件")
            // 接受图片的地方（头像、背景、聊天里发图）多给一个「拍照」
            val wantsImage = mimes.isEmpty() || mimes.any { it == null || it.startsWith("image/") || it == "*/*" }
            if (wantsImage) cameraIntent()?.let { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
            return try {
                pickFiles.launch(chooser)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                cameraFile = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                // 只放行麦克风（语音输入类扩展）；拍照走系统相机，不需要相机权限
                val needed = request.resources.mapNotNull {
                    if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) Manifest.permission.RECORD_AUDIO else null
                }
                if (needed.isEmpty()) {
                    request.deny()
                } else {
                    pendingPermission = request
                    askPermissions.launch(needed.toTypedArray())
                }
            }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // window.open(): catch the first navigation of a throwaway WebView and hand it to the browser.
            val popup = WebView(this@MainActivity)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (isLocal(uri)) webView.loadUrl(uri.toString()) else openExternal(uri)
                    v.destroy()
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView?.let { onHideCustomView() }
            customView = view
            customViewCallback = callback
            root.setPadding(0, 0, 0, 0)
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            ViewCompat.requestApplyInsets(root)
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                Log.w(TAG, "console: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            }
            return true
        }
    }

    // ------------------------------------------------------------------ back

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (customView != null) {
                (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
                return
            }
            if (!pageShown) {
                moveTaskToBack(true)
                return
            }
            webView.evaluateJavascript("window.__taverndroidBack ? window.__taverndroidBack() : false") { result ->
                if (result == "true") {
                    // SillyTavern closes the topmost panel/popup on Escape.
                    webView.requestFocus()
                    webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                    webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                } else {
                    moveTaskToBack(true)
                }
            }
        }
    }

    // ------------------------------------------------------------- downloads

    private fun downloadHttp(url: String, userAgent: String, name: String, mime: String?) {
        toast("正在下载 $name…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                if (prefs.lanAccess && isLocal(Uri.parse(url))) {
                    val token = Base64.encodeToString("${prefs.lanUser}:${prefs.lanPassword}".toByteArray(), Base64.NO_WRAP)
                    connection.setRequestProperty("Authorization", "Basic $token")
                }
                val dir = StPaths(this@MainActivity).downloads.apply { mkdirs() }
                val temp = File.createTempFile("dl", null, dir)
                connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                val type = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: connection.contentType?.substringBefore(';') ?: ""
                val pending = fileSaver.adopt(temp, name, type)
                withContext(Dispatchers.Main) { finishSave(pending) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("下载失败：${e.message}") }
            }
        }
    }

    private fun finishSave(pending: FileSaver.Pending) {
        if (Build.VERSION.SDK_INT >= 29) {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching { fileSaver.publish(pending) }
                withContext(Dispatchers.Main) {
                    val uri = result.getOrNull()
                    if (uri != null) {
                        toast("已保存到 下载/${FileSaver.FOLDER}/${pending.name}")
                        notifySaved(pending, uri)
                    } else {
                        toast("保存 ${pending.name} 失败：${result.exceptionOrNull()?.message}")
                    }
                }
            }
        } else {
            pendingLegacySave = pending
            legacySave.launch(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(pending.mime).putExtra(Intent.EXTRA_TITLE, pending.name)
            )
        }
    }

    private fun notifySaved(pending: FileSaver.Pending, uri: Uri) {
        if (!canNotify()) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, pending.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(
            this, pending.name.hashCode(), Intent.createChooser(view, pending.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, ServerService.CHANNEL_FILES)
            .setSmallIcon(Appearance.notificationSmallIcon(this))
            .setContentTitle(pending.name)
            .setContentText("已保存到 下载/${FileSaver.FOLDER}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        @SuppressLint("MissingPermission")
        fun post() = NotificationManagerCompat.from(this).notify(pending.name.hashCode(), n)
        post()
    }

    // ---------------------------------------------------------------- bridge

    /** Methods callable from the page as window.TavernDroidNative.* (see assets/taverndroid.js). */
    private inner class Bridge {
        @JavascriptInterface
        fun saveBegin(name: String, mime: String, @Suppress("UNUSED_PARAMETER") size: Double): String =
            fileSaver.begin(name, mime)

        @JavascriptInterface
        fun saveChunk(id: String, base64: String) = fileSaver.append(id, Base64.decode(base64, Base64.DEFAULT))

        @JavascriptInterface
        fun saveEnd(id: String) {
            val pending = fileSaver.take(id) ?: return
            runOnUiThread { finishSave(pending) }
        }

        @JavascriptInterface
        fun saveAbort(id: String) = fileSaver.abort(id)

        @JavascriptInterface
        fun toast(message: String) = runOnUiThread { this@MainActivity.toast(message) }

        @JavascriptInterface
        fun openSettings() = runOnUiThread { this@MainActivity.openSettings() }

        @JavascriptInterface
        fun generationStarted(@Suppress("UNUSED_PARAMETER") type: String) {
            Generation.started(applicationContext)
            runOnUiThread {
                keepRendererImportant(true)
                Background.maybeAskOnce(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun generationEnded() {
            Generation.release()
            runOnUiThread { keepRendererImportant(false) }
        }

        @JavascriptInterface
        fun replyReceived(name: String, text: String) = runOnUiThread { notifyReply(name, text) }

        @JavascriptInterface
        fun pageReady() = runOnUiThread {
            revealPage()
            applyPendingPerfTweak()
        }

        /** SillyTavern 初始化完成（设置已加载）。 */
        @JavascriptInterface
        fun appReady() = runOnUiThread { applyPendingPerfTweak() }

        @JavascriptInterface
        fun ttsVoices(): String = speech().voices()

        @JavascriptInterface
        fun ttsSpeak(id: String, text: String, voice: String, lang: String, rate: Float, pitch: Float, volume: Float) =
            speech().speak(id, text, voice, lang, rate, pitch, volume)

        @JavascriptInterface
        fun ttsStop() = speech().stop()

        /** 整体缩放百分比（80～150）。 */
        @JavascriptInterface
        fun uiScale(): Int = prefs.uiScale

        /** WebView 的宽度，单位是 CSS 像素（100% 缩放时）。 */
        @JavascriptInterface
        fun viewWidthDp(): Float = webView.width / resources.displayMetrics.density
    }

    private fun notifyReply(name: String, text: String) {
        if (resumed || !prefs.notifyReplies || !canNotify()) return
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, ServerService.CHANNEL_REPLIES)
            .setSmallIcon(Appearance.notificationSmallIcon(this))
            .setContentTitle(name)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Android 7 (no channels): sound, vibration, pop-up
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .apply {
                if (prefs.notifyReplyText) {
                    setContentText(TextUtils.ellipsize(text, android.text.TextPaint(), 2000f, TextUtils.TruncateAt.END))
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                } else {
                    setContentText("回复了你")
                }
            }
            .build()
        // Remove the previous reply first: an identical update to a notification that is still shown ("New reply"
        // again) counts as non-interruptive, so Android would replace it without sound or vibration.
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(REPLY_NOTIFICATION_ID)
        @SuppressLint("MissingPermission")
        fun post() = nm.notify(REPLY_NOTIFICATION_ID, n)
        post()
    }

    // ----------------------------------------------------------------- utils

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) &&
            getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotificationPermission &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            prefs.askedNotificationPermission = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    /** 给系统相机准备一个输出文件，返回拍照 Intent；没有相机应用时返回 null。 */
    private fun cameraIntent(): Intent? {
        val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (capture.resolveActivity(packageManager) == null) return null
        return runCatching {
            val dir = StPaths(this).camera.apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
            cameraFile = file
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            capture.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Jiuguan"
        private val BG = Color.rgb(0x15, 0x11, 0x0e)
        private val SPLASH_BG = Color.rgb(0x01, 0x10, 0x25) // 和 res 里的 splash_bg、默认开屏图的底色一致
        private val PAGE_BG = Color.rgb(0x17, 0x17, 0x17)
        private val MUTED = Color.rgb(0xb9, 0xae, 0x9c)
        private const val REPLY_NOTIFICATION_ID = 2
        private const val START_GRACE_MS = 8000L
        const val EXTRA_OPEN = "open"
    }
}

/** 让聊天页面离开（导入数据前用），见 [MainActivity.unloadPage]。 */
object PageControl {
    val unloads = MutableStateFlow(0)

    fun unload() {
        unloads.value = unloads.value + 1
    }
}
