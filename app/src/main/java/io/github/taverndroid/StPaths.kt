package io.github.taverndroid

import android.content.Context
import java.io.File

/**
 * 手机上的目录布局（都在 app 私有目录里）。程序和数据分开放：
 *   st/app/          SillyTavern 程序本体（升级时整个替换）
 *   st/data/         数据根目录：用户、角色、聊天、世界书、预设、secrets.json…
 *   st/config.yaml   服务器配置
 *   st/plugins/      服务端插件（app/plugins 是指向这里的链接）
 *   st/extensions/   全局第三方扩展（app/public/scripts/extensions/third-party 指向这里）
 * 升级只动 st/app，其余都不碰。
 */
class StPaths(context: Context) {
    val root = File(context.filesDir, "st")
    val app = File(root, "app")
    val appVersion = File(root, "app.version")
    val appNew = File(root, "app.new")
    /** 解压 APK 自带版本用的临时目录（和升级用的 app.new 分开，互不影响）。 */
    val appBundle = File(root, "app.bundle")
    val appOld = File(root, "app.old")
    val data = File(root, "data")
    val config = File(root, "config.yaml")
    val plugins = File(root, "plugins")
    val extensions = File(root, "extensions")
    val home = File(root, "home")
    val bin = File(root, "bin")
    val gitCore = File(bin, "git-core")
    val runtime = File(root, "runtime")
    val gitTemplates = File(runtime, "git-templates")
    val npm = File(runtime, "npm")
    val npmVersion = File(runtime, "npm.version")
    val logs = File(root, "logs")
    val serverLog = File(logs, "server.log")
    val previousServerLog = File(logs, "server.previous.log")
    val pidFile = File(root, "node.pid")
    val tmp = File(context.cacheDir, "tmp")
    val downloads = File(context.cacheDir, "downloads")
    val camera = File(context.cacheDir, "camera")
    val upgrade = File(context.cacheDir, "upgrade")
    val npmCache = File(context.cacheDir, "npm-cache")
    val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val node = File(nativeDir, "libnode.so")
    val preload = File(runtime, "android-preload.cjs")
    val certs = File(runtime, "cert.pem")
    val opensslConf = File(runtime, "openssl.cnf")

    /** 程序目录里，指向共享插件/扩展目录的两个位置。 */
    val appPluginsLink = File(app, "plugins")
    val appExtensionsLink = File(app, "public/scripts/extensions/third-party")
}
