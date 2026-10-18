package io.github.taverndroid

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom

/** App 自己的设置。SillyTavern 自己的设置仍在它的数据目录和 config.yaml 里。 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("jiuguan", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(value) = sp.edit { putInt("port", value) }

    /** 对局域网开放（默认关闭，只监听 127.0.0.1）。打开后其他设备要用下面的账号密码登录。 */
    var lanAccess: Boolean
        get() = sp.getBoolean("lan", false)
        set(value) = sp.edit { putBoolean("lan", value) }

    var lanUser: String
        get() = sp.getString("lanUser", null) ?: "tavern"
        set(value) = sp.edit { putString("lanUser", value) }

    var lanPassword: String
        get() = sp.getString("lanPassword", null) ?: randomPassword().also { lanPassword = it }
        set(value) = sp.edit { putString("lanPassword", value) }

    // ---------------------------------------------------------------- 显示

    /** 整体缩放，百分比（80～150）。 */
    var uiScale: Int
        get() = sp.getInt("uiScale", 100).coerceIn(MIN_SCALE, MAX_SCALE)
        set(value) = sp.edit { putInt("uiScale", value.coerceIn(MIN_SCALE, MAX_SCALE)) }

    var hideStatusBar: Boolean
        get() = sp.getBoolean("hideStatusBar", false)
        set(value) = sp.edit { putBoolean("hideStatusBar", value) }

    var hideNavigationBar: Boolean
        get() = sp.getBoolean("hideNavigationBar", false)
        set(value) = sp.edit { putBoolean("hideNavigationBar", value) }

    /** 用户点了「省电流畅」，等酒馆页面打开后去改那四项设置。 */
    var pendingPerfTweak: Boolean
        get() = sp.getBoolean("pendingPerfTweak", false)
        set(value) = sp.edit { putBoolean("pendingPerfTweak", value) }

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keepScreenOn", false)
        set(value) = sp.edit { putBoolean("keepScreenOn", value) }

    // ---------------------------------------------------------------- 后台

    var notifyReplies: Boolean
        get() = sp.getBoolean("notifyReplies", true)
        set(value) = sp.edit { putBoolean("notifyReplies", value) }

    /** 通知里显示回复内容（关闭时只显示“某某回复了”）。 */
    var notifyReplyText: Boolean
        get() = sp.getBoolean("notifyReplyText", false)
        set(value) = sp.edit { putBoolean("notifyReplyText", value) }

    /** 生成回复期间保持 CPU 唤醒，息屏时回复也能写完。 */
    var wakeDuringGeneration: Boolean
        get() = sp.getBoolean("wakeDuringGeneration", true)
        set(value) = sp.edit { putBoolean("wakeDuringGeneration", value) }

    // ---------------------------------------------------------------- 高级

    /** 强制开启服务端插件（不管 config.yaml 里怎么写）。 */
    var serverPlugins: Boolean
        get() = sp.getBoolean("serverPlugins", true)
        set(value) = sp.edit { putBoolean("serverPlugins", value) }

    var webDebugging: Boolean
        get() = sp.getBoolean("webDebugging", false)
        set(value) = sp.edit { putBoolean("webDebugging", value) }

    /** Node.js 内存上限（MB），0 = 自动。 */
    var nodeHeapMb: Int
        get() = sp.getInt("nodeHeapMb", 0)
        set(value) = sp.edit { putInt("nodeHeapMb", value) }

    /** 升级 SillyTavern 时装依赖用的 npm 源。 */
    var npmRegistry: String
        get() = sp.getString("npmRegistry", null) ?: NPM_OFFICIAL
        set(value) = sp.edit { putString("npmRegistry", value) }

    var askedBackground: Boolean
        get() = sp.getBoolean("askedBackground", false)
        set(value) = sp.edit { putBoolean("askedBackground", value) }

    var askedNotificationPermission: Boolean
        get() = sp.getBoolean("askedNotif", false)
        set(value) = sp.edit { putBoolean("askedNotif", value) }

    companion object {
        const val DEFAULT_PORT = 8800
        const val MIN_SCALE = 80
        const val MAX_SCALE = 150
        const val NPM_OFFICIAL = "https://registry.npmjs.org/"
        const val NPM_MIRROR = "https://registry.npmmirror.com/"

        fun randomPassword(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
            val random = SecureRandom()
            return (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
