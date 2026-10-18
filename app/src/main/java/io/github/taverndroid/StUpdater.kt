package io.github.taverndroid

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * 在手机上直接升级 SillyTavern 本体（跟 Termux 里 git pull + npm install 一样的效果）：
 *   查 GitHub 上的最新正式版 → 自动备份数据 → 下载源码 → 用 app 自带的 npm 装依赖（在 st/app.new 里）
 *   → 停服务器 → 用新程序替换 st/app → 启动。
 * 数据、config.yaml、插件、扩展都不在 st/app 里，升级不会碰到它们。
 * 下载和装依赖期间旧版本照常运行，只有最后替换的几秒钟会停一下。
 */
object StUpdater {
    data class Release(val version: String, val tag: String, val notes: String, val page: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val installed: String, val latest: String) : State
        data class Available(val installed: String, val release: Release) : State
        data class Working(val message: String, val progress: Float?) : State
        data class Done(val version: String, val backup: String) : State
        data class Failed(val message: String) : State
    }

    private const val TAG = "Jiuguan"
    private const val REPO = "SillyTavern/SillyTavern"
    /** 早于这个版本的 SillyTavern 有能被远程删插件的漏洞，不提供。 */
    const val MIN_VERSION = "1.18.0"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.e(TAG, "updater failed", t) }
    )
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var npm: Process? = null

    /** 正在下载或装依赖（这时 st/app.new 不能被清理）。 */
    val isBusy: Boolean get() = job?.isActive == true && _state.value is State.Working

    fun checkForUpdate(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = State.Checking
            _state.value = try {
                val installed = Installer(app, StPaths(app)).installedStVersion() ?: "0"
                val release = latestRelease()
                if (Fs.compareVersions(release.version, installed) > 0) State.Available(installed, release)
                else State.UpToDate(installed, release.version)
            } catch (e: Exception) {
                State.Failed("检查更新失败：${e.message ?: e.javaClass.simpleName}。请确认能连上 GitHub（可以开 VPN）。")
            }
        }
    }

    fun upgrade(context: Context, release: Release) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { runUpgrade(app, release) }
    }

    fun reset() {
        if (job?.isActive != true) _state.value = State.Idle
    }

    /** 退出 app 时调用：停掉正在进行的升级（半成品留在 st/app.new，下次启动时清理）。 */
    fun cancelRunning() {
        job?.cancel()
        npm?.destroyForcibly()
        npm = null
    }

    // ---------------------------------------------------------------- 检查

    private fun latestRelease(): Release {
        val json = JSONObject(httpGetText("https://api.github.com/repos/$REPO/releases/latest"))
        val tag = json.getString("tag_name")
        val version = tag.removePrefix("v")
        require(Fs.compareVersions(version, MIN_VERSION) >= 0) { "最新版 $version 比 $MIN_VERSION 还旧，不升级" }
        return Release(
            version = version,
            tag = tag,
            notes = json.optString("body").trim(),
            page = json.optString("html_url", "https://github.com/$REPO/releases"),
        )
    }

    private fun httpGetText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Jiuguan-Android")
            setRequestProperty("Accept", "application/vnd.github+json, */*")
        }

    // ---------------------------------------------------------------- 升级

    private suspend fun runUpgrade(context: Context, release: Release) {
        val paths = StPaths(context)
        val installer = Installer(context, paths)
        val log = ServerController.log
        fun step(message: String, progress: Float? = null) {
            _state.value = State.Working(message, progress)
        }
        try {
            log.add("[升级] 准备升级到 SillyTavern ${release.version}")

            step("正在备份数据（升级前自动备份）…")
            val backup = BackupManager(context).backupToDownloads("升级前", auto = true) { step("正在备份数据：$it") }
            log.add("[升级] 已备份到 $backup")

            step("正在下载 SillyTavern ${release.version}…")
            paths.upgrade.mkdirs()
            val zip = File(paths.upgrade, "st-${release.version}.zip")
            download("https://github.com/$REPO/archive/refs/tags/${release.tag}.zip", zip) { done, total ->
                val mb = "%.1f".format(done / 1048576.0)
                if (total > 0) step("正在下载 SillyTavern ${release.version}… $mb MB", done.toFloat() / total)
                else step("正在下载 SillyTavern ${release.version}… $mb MB")
            }

            step("正在解压…")
            Fs.deleteTree(paths.appNew)
            paths.appNew.mkdirs()
            zip.inputStream().use { Fs.unzip(it, paths.appNew, stripFirst = true) }
            zip.delete()
            check(File(paths.appNew, "server.js").exists()) { "下载的压缩包里没有 server.js" }
            // 这两个目录由 app 换成指向共享目录的链接，源码包里自带的空目录先去掉
            Fs.deleteTree(File(paths.appNew, "public/scripts/extensions/third-party"))
            File(paths.appNew, "plugins").listFiles()?.forEach { if (it.isDirectory) Fs.deleteTree(it) }

            step("正在安装依赖（npm，可能要几分钟）…")
            runNpm(context, paths, paths.appNew)
            currentCoroutineContext().ensureActive()

            step("正在切换到新版本…")
            ServerController.stop()
            installer.swapIn(paths.appNew)
            installer.writeUpgradeMarker(release.version)
            log.add("[升级] 已切换到 SillyTavern ${release.version}，正在启动")
            ServerController.start(context)
            _state.value = State.Done(release.version, backup)
        } catch (e: CancellationException) {
            npm?.destroyForcibly()
            _state.value = State.Idle
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "upgrade failed", e)
            log.add("[升级] 失败：${e.message ?: e.javaClass.simpleName}")
            runCatching { Fs.deleteTree(paths.appNew) }
            _state.value = State.Failed(
                "升级没有完成：${e.message ?: e.javaClass.simpleName}\n现在用的还是原来的版本，数据没有变化。详细过程在日志里。"
            )
            // 万一停在了替换途中，保证服务器重新跑起来
            if (!ServerController.isActive) ServerController.start(context)
        }
    }

    private suspend fun download(url: String, target: File, progress: (Long, Long) -> Unit) {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) throw IOException("下载失败 HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            var done = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            progress(done, total)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("下载不完整（$done / $total 字节）")
        } finally {
            connection.disconnect()
        }
    }

    /** 用 app 自带的 Node.js + npm 在 [dir] 里装生产依赖。 */
    private suspend fun runNpm(context: Context, paths: StPaths, dir: File) {
        val npmCli = File(paths.npm, "bin/npm-cli.js")
        check(npmCli.exists()) { "这个安装包里没有带 npm，无法在手机上升级" }
        // 确保 node / git 的链接和证书都在（服务器至少启动过一次就已经有了）
        if (!File(paths.bin, "node").exists()) {
            Installer(context, paths).ensureInstalled { _, _ -> }
        }
        val prefs = Prefs(context)
        paths.npmCache.mkdirs()
        paths.tmp.mkdirs()
        val lock = File(dir, "package-lock.json").exists()
        val command = listOf(
            paths.node.absolutePath, "--require", paths.preload.absolutePath, npmCli.absolutePath,
            if (lock) "ci" else "install",
            "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund", "--no-update-notifier",
            "--progress=false", "--loglevel=http",
            "--registry", prefs.npmRegistry,
            "--cache", paths.npmCache.absolutePath,
        )
        val pb = ProcessBuilder(command).directory(dir).redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = paths.home.absolutePath
        env["TMPDIR"] = paths.tmp.absolutePath
        env["LD_LIBRARY_PATH"] = paths.nativeDir.absolutePath
        env["PATH"] = "${paths.bin.absolutePath}:/system/bin:/system/xbin"
        env["OPENSSL_CONF"] = paths.opensslConf.absolutePath
        env["SSL_CERT_FILE"] = paths.certs.absolutePath
        env["GIT_EXEC_PATH"] = paths.gitCore.absolutePath
        env["GIT_SSL_CAINFO"] = paths.certs.absolutePath
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["NODE_ENV"] = "production"
        env["NO_COLOR"] = "1"
        env["npm_config_update_notifier"] = "false"

        ServerController.log.add("[升级] npm ${if (lock) "ci" else "install"}（源：${prefs.npmRegistry}）")
        val p = pb.start()
        npm = p
        var fetched = 0
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("npm http")) {
                        // 每个包一行（下载或命中缓存），只拿来显示进度，不写进日志
                        fetched++
                        if (fetched % 10 == 0) _state.value = State.Working("正在安装依赖（已处理 $fetched 个包）…", null)
                    } else if (line.isNotBlank()) {
                        ServerController.log.add("[npm] $line")
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被结束时管道会关掉
        }
        val code = p.waitFor()
        npm = null
        currentCoroutineContext().ensureActive()
        check(code == 0) { "npm 安装依赖失败（退出码 $code）。可以在 App 设置里换一个 npm 源再试" }
        check(File(dir, "node_modules/express").exists()) { "依赖没有装全" }
        _state.value = State.Working("正在清理用不到的文件…", null)
        trimNodeModules(File(dir, "node_modules"))
    }

    /** 跟 tools/prepare.sh 一样，删掉运行时用不到的文件（source map、类型声明、说明文档），省一些空间。 */
    private fun trimNodeModules(dir: File) {
        val junk = Regex("""(?i).*\.(map|d\.ts|d\.mts|d\.cts)$|^(readme|changelog|history\.md).*""")
        dir.walkTopDown().onEnter { !Fs.isLink(it) }.forEach { f ->
            if (f.isFile && junk.matches(f.name)) f.delete()
        }
    }
}
