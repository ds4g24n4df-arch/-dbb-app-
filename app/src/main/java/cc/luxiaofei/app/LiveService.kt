package cc.luxiaofei.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台服务：让 App 的进程在**屏幕关掉、切到抖音**之后还能活着。
 *
 * 说清楚它管什么、不管什么（这点很容易被误解）：
 *   - 管：进程不被系统随手回收、读屏采集能一直收到公屏、回到前台时页面还是原来那个
 *     （不会因为进程被杀而重新加载、开播状态全丢）。
 *   - 不管：**网页里的 JS 计时器在后台会被系统降频甚至冷冻**。也就是说，
 *     光靠前台服务，锁屏之后"网页自己一句一句往下念"这件事仍可能变慢或停住。
 *     真要"锁屏也照念"，得把播放循环搬到原生（用 MediaPlayer + 同一套接口），
 *     那是下一步的事 —— 现在先保证进程和采集不断，屏幕常亮时体验和网页一致。
 *
 * 通知栏那张卡片就是安卓对前台服务的要求（顺便也是用户的"我还在播"提示）。
 */
class LiveService : Service() {

    companion object {
        private const val CHANNEL_ID = "dbb-live"
        private const val NOTIFY_ID = 1001
        /** 别的组件想知道服务在不在（页面显示状态用） */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFY_ID, buildNotification())
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统杀掉后自动重来：长播期间用户不该管这个
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                // LOW：这张卡片是"常驻状态"，不该每次变化都响一声
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.service_channel_desc)
            },
        )
    }

    private fun buildNotification(): Notification {
        // 点通知回到 App（开播页面就在那里）
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
