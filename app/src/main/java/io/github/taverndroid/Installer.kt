package io.github.taverndroid

import android.content.Context
import android.system.Os
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * 把 APK 里自带的 SillyTavern（assets/st-bundle.zip）解压到 st/app，
 * 并在每次启动时刷新运行时文件、可执行文件链接和插件/扩展目录链接。
 *
 * st/app.version 记录当前程序来源：
 *   bundle:<版本>   来自 APK 自带包
 *   upgrade:<版本>  在 app 里升级过（只有 APK 自带的版本更新时才会被覆盖回去）
 */
class Installer(private val context: Context, private val paths: StPaths) {

    fun interface Progress {
        fun update(message: String, fraction: Float?)
    }

    fun bundledVersion(): String =
        context.assets.open("st-bundle.version").use { it.readBytes().decodeToString().trim() }

    private fun marker(): Pair<String, String>? {
        val raw = paths.appVersion.takeIf { it.exists() }?.readText()?.trim() ?: return null
        return when {
            raw.startsWith("bundle:") -> "bundle" to raw.removePrefix("bundle:")
            raw.startsWith("upgrade:") -> "upgrade" to raw.removePrefix("upgrade:")
            raw.isEmpty() -> null
            else -> "bundle" to raw
        }
    }

    /** 当前 st/app 里 SillyTavern 的版本号（读 package.json）。 */
    fun installedStVersion(): String? = runCatching {
        val json = File(paths.app, "package.json").readText()
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    }.getOrNull()

    fun isUpgraded(): Boolean = marker()?.first == "upgrade"

    fun ensureInstalled(progress: Progress) {
        listOf(paths.root, paths.data, paths.home, paths.logs, paths.tmp, paths.downloads, paths.plugins, paths.extensions)
            .forEach { it.mkdirs() }
        // 上次升级中途被打断留下的半成品（正在升级时不能删）
        if (paths.appNew.exists() && !StUpdater.isBusy) Fs.deleteTree(paths.appNew)
        if (paths.appBundle.exists()) Fs.deleteTree(paths.appBundle)
        if (paths.appOld.exists() && paths.app.exists()) Fs.deleteTree(paths.appOld)
        // 上次替换到一半（旧程序已经挪走、新程序没放进来）：先把旧的放回去
        if (!paths.app.exists() && paths.appOld.exists()) paths.appOld.renameTo(paths.app)

        val bundled = bundledVersion()
        if (needsBundle(bundled)) extractBundle(bundled, progress)
        progress.update("正在准备运行环境…", null)
        installRuntime()
        installNpm(progress)
        seedCache()
        linkExecutables()
        linkSharedDirs()
    }

    private fun needsBundle(bundled: String): Boolean {
        if (!File(paths.app, "server.js").exists()) return true
        val (kind, version) = marker() ?: return true
        return when (kind) {
            "bundle" -> version != bundled
            // 在 app 里升级过：只有新装的 APK 自带更高版本时才换回 APK 自带的
            else -> Fs.compareVersions(bundled, version) > 0
        }
    }

    /** 下次启动时重新解压 APK 自带的 SillyTavern（「修复程序文件」和「回到内置版本」用）。 */
    fun invalidate() {
        paths.appVersion.delete()
    }

    fun writeUpgradeMarker(version: String) {
        paths.appVersion.writeText("upgrade:$version")
    }

    private fun extractBundle(version: String, progress: Progress) {
        val staging = paths.appBundle
        Fs.deleteTree(staging)
        staging.mkdirs()

        val label = version.substringBefore('-')
        val total = context.assets.openFd("st-bundle.zip").use { it.length }.coerceAtLeast(1)
        var lastReport = -1
        context.assets.open("st-bundle.zip").use { raw ->
            val counting = CountingInputStream(raw)
            Fs.unzip(counting, staging, stripFirst = false) {
                val percent = (counting.count * 100 / total).toInt()
                if (percent != lastReport) {
                    lastReport = percent
                    progress.update("正在解压 SillyTavern $label… $percent%", percent / 100f)
                }
            }
        }
        swapIn(staging)
        paths.appVersion.writeText("bundle:$version")
    }

    /** 用新目录替换 st/app。旧目录里的插件/扩展先搬到共享目录，再删除旧目录（不跟随链接）。 */
    fun swapIn(newApp: File) {
        if (paths.app.exists()) {
            rescueShared(paths.appPluginsLink, paths.plugins)
            rescueShared(paths.appExtensionsLink, paths.extensions)
            Fs.deleteTree(paths.appOld)
            if (!paths.app.renameTo(paths.appOld)) Fs.deleteTree(paths.app)
        }
        if (!newApp.renameTo(paths.app)) {
            // 放不进去就把原来的程序放回去
            if (!paths.app.exists()) paths.appOld.renameTo(paths.app)
            error("无法把 SillyTavern 放到位")
        }
        Fs.deleteTree(paths.appOld)
    }

    /** 如果程序目录里的插件/扩展目录还是真实文件夹（老版本布局），把里面的东西搬到共享目录。 */
    private fun rescueShared(dir: File, shared: File) {
        if (!dir.exists() || Fs.isLink(dir)) return
        shared.mkdirs()
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory && !Fs.isLink(child)) {
                val dest = File(shared, child.name)
                if (!dest.exists() && !child.renameTo(dest)) Fs.copyTree(child, dest)
            }
        }
    }

    /**
     * 让 st/app/plugins 和 st/app/public/scripts/extensions/third-party 指向 st/ 下的共享目录。
     * SillyTavern 自带的顶层文件（plugins/package.json 等）复制进共享目录。
     */
    private fun linkSharedDirs() {
        linkShared(paths.appPluginsLink, paths.plugins)
        linkShared(paths.appExtensionsLink, paths.extensions)
    }

    private fun linkShared(link: File, shared: File) {
        shared.mkdirs()
        if (Fs.isLink(link)) {
            if (runCatching { Os.readlink(link.absolutePath) }.getOrNull() == shared.absolutePath) return
            link.delete()
        } else if (link.exists()) {
            link.listFiles()?.forEach { child ->
                val dest = File(shared, child.name)
                if (child.isFile && !Fs.isLink(child)) {
                    child.copyTo(dest, overwrite = true)
                } else if (!dest.exists() && !child.renameTo(dest)) {
                    Fs.copyTree(child, dest)
                }
            }
            Fs.deleteTree(link)
        }
        link.parentFile?.mkdirs()
        Os.symlink(shared.absolutePath, link.absolutePath)
    }

    private fun installRuntime() {
        paths.runtime.mkdirs()
        paths.gitTemplates.mkdirs()
        for (name in context.assets.list("runtime").orEmpty()) {
            context.assets.open("runtime/$name").use { input ->
                File(paths.runtime, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** 自带的 npm（只在 app 内升级 SillyTavern 时用到）。 */
    private fun installNpm(progress: Progress) {
        val bundled = runCatching {
            context.assets.open("npm.version").use { it.readBytes().decodeToString().trim() }
        }.getOrNull() ?: return
        val installed = paths.npmVersion.takeIf { it.exists() }?.readText()?.trim()
        if (installed == bundled && File(paths.npm, "bin/npm-cli.js").exists()) return
        progress.update("正在准备 npm…", null)
        Fs.deleteTree(paths.npm)
        paths.npm.mkdirs()
        context.assets.open("npm.zip").use { Fs.unzip(it, paths.npm, stripFirst = false) }
        paths.npmVersion.writeText(bundled)
    }

    /** SillyTavern 首次用到时才会去 GitHub 下载的分词器（见 tools/prepare.sh）。 */
    private fun seedCache() {
        val cache = File(paths.data, "_cache").apply { mkdirs() }
        for (name in context.assets.list("seed-cache").orEmpty()) {
            val target = File(cache, name)
            val unpacked = File(cache, name.removeSuffix(".gz"))
            if (target.exists() || unpacked.exists()) continue
            context.assets.open("seed-cache/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun linkExecutables() {
        Fs.deleteTree(paths.bin)
        paths.gitCore.mkdirs()
        val native = paths.nativeDir
        fun link(target: String, link: File) {
            Os.symlink(File(native, target).absolutePath, link.absolutePath)
        }
        link("libnode.so", File(paths.bin, "node"))
        link("libgit.so", File(paths.bin, "git"))
        link("libgit.so", File(paths.gitCore, "git"))
        for (name in listOf("git-remote-http", "git-remote-https", "git-remote-ftp", "git-remote-ftps")) {
            link("libgitremotehttp.so", File(paths.gitCore, name))
        }
    }

    class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
