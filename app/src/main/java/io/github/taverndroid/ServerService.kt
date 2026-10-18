package io.github.taverndroid

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：app 切到后台时让 SillyTavern 继续跑。
 * 打开 app 才启动；在最近任务里划掉 app，就把 Node 进程、前台服务、唤醒锁全部结束，并退出整个 app 进程。
 */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> {
                exitApp()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> ServerController.restart(this)
        }
        goForeground(ServerController.state.value)
        ServerController.start(this)
        if (watcher == null) {
            watcher = scope.launch {
                ServerController.state.collect { state ->
                    goForeground(state)
                }
            }
        }
        // 被系统杀掉后不要自己复活：跟普通 app 一样，下次用户打开时再启动。
        return START_NOT_STICKY
    }

    /** 用户在最近任务里把 app 划掉：彻底停止。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        exitApp()
    }

    /** 结束 Node 进程、释放唤醒锁、撤掉前台通知、关掉界面，最后结束 app 进程本身。 */
    private fun exitApp() {
        StUpdater.cancelRunning()
        ServerController.shutdownNow()
        Generation.release()
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        runCatching {
            getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        }
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        foreground = false
        scope.cancel()
        Generation.release()
        super.onDestroy()
    }

    private fun goForeground(state: ServerController.State) {
        val notification = buildNotification(this, state)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        foreground = true
    }

    companion object {
        const val ACTION_EXIT = "io.github.taverndroid.EXIT"
        const val ACTION_RESTART = "io.github.taverndroid.RESTART"
        const val CHANNEL_SERVER = "server"
        // Channel settings are fixed once created, so a changed default needs a new id. "replies" (1.0.0-1.0.2)
        // was IMPORTANCE_DEFAULT without vibration: a quiet sound, no vibration, no pop-up.
        const val CHANNEL_REPLIES = "replies_alert"
        private val OLD_CHANNELS = listOf("replies")
        const val CHANNEL_FILES = "files"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ServerService::class.java))
        }

        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ServerService::class.java).setAction(ACTION_RESTART)
            )
        }

        /** 退出整个 app（通知栏「退出」按钮）。 */
        fun exit(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_EXIT))
        }

        /** 前台服务真的挂着常驻通知时才是 true；没挂着就不能补发，不然会冒出一条没人管、划不掉的通知。 */
        @Volatile
        private var foreground = false

        /** 换了图标以后，常驻通知还在的话马上刷新成新图标（不然要等下次状态变化）。 */
        @SuppressLint("MissingPermission")
        fun refreshNotification(context: Context) {
            if (!foreground) return
            val state = ServerController.state.value
            runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, buildNotification(context, state)) }
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVER, "酒馆后台", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "酒馆在后台运行时显示"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_REPLIES, "新回复", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "app 在后台时角色回复了"
                    enableVibration(true)
                }
            )
            OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_FILES, "已保存的文件", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "导出和下载的文件已存到下载目录"
                }
            )
        }

        private fun buildNotification(context: Context, state: ServerController.State): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, ServerService::class.java).setAction(ACTION_EXIT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val prefs = Prefs(context)
            val text = when (state) {
                is ServerController.State.Running ->
                    if (prefs.lanAccess) "营业中 · 局域网 ${Network.lanUrls(prefs.port).firstOrNull() ?: state.url}"
                    else "营业中"
                is ServerController.State.Preparing -> state.message
                ServerController.State.Starting -> "正在开门…"
                is ServerController.State.Failed -> "已停止：${state.message}"
                ServerController.State.Stopped -> "已停止"
            }
            return NotificationCompat.Builder(context, CHANNEL_SERVER)
                .setSmallIcon(Appearance.notificationSmallIcon(context))
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, "退出", stop)
                .build()
        }
    }
}

/** 生成回复期间保持 CPU 唤醒（否则息屏后回复会卡住）。最多 20 分钟，回复结束就释放。 */
object Generation {
    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    @SuppressLint("WakelockTimeout")
    fun started(context: Context) {
        if (!Prefs(context).wakeDuringGeneration) return
        val lock = wakeLock ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jiuguan:generation")
            .also { it.setReferenceCounted(false); wakeLock = it }
        // Safety timeout in case the page never reports the end of the generation.
        lock.acquire(20 * 60 * 1000L)
    }

    @Synchronized
    fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }
}
