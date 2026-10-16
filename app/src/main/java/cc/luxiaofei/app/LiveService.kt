package cc.luxiaofei.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 前台服务：进程保活 + **原生出声**。
 *
 * 为什么要把"念"搬到原生：网页里的 JS 计时器在后台会被系统降频甚至冷冻 ——
 * 一开抖音直播（我们 App 就退到后台了），"网页自己一句一句往下念"就会变慢、甚至停住。
 * 所以播放循环放在原生：只要服务还活着，**抖音在前台、屏幕锁着，它都照念**。
 *
 * 取稿子的接口和网页**完全一样**（/api/live/next → /api/tts），所以音色、话术、
 * 节奏、算力计费全都不变；变的只是"谁来放这一段"。
 *
 * 两个关键约定：
 *   1. 取稿子时带 `?player=app`：服务端据此知道"这是手机原生在取"。如果这台手机被设成
 *      「本机出声：关」（账号设置 appSpeak=false），服务端会回 `{skip:true}` 且**不推进游标** ——
 *      队列是共用的，推进了就等于"把这句吃掉又不念"，另一台设备再也拿不到它。
 *   2. 一次只放一段，放完 + 停顿（gapSec）再取下一段，节奏与网页那套一致。
 *
 * 通知栏那张卡片是安卓对前台服务的要求（顺便也是用户的"我还在播"提示）。
 */
class LiveService : Service() {

    companion object {
        private const val CHANNEL_ID = "dbb-live"
        private const val NOTIFY_ID = 1001

        /** 空闲时多久问一次"有没有在播"（没开播时服务端会很快回，代价很小） */
        private const val IDLE_POLL_MS = 4000L
        /** 出错（网络/接口）后的退避，别把手机和服务器都刷爆 */
        private const val ERROR_BACKOFF_MS = 6000L
        /** 单条 TTS 最多等多久 */
        private const val TTS_TIMEOUT_MS = 25_000
        /** 单条音频最多放多久（兜底，防止播放器卡住不回调） */
        private const val PLAY_TIMEOUT_SEC = 120L

        /** 别的组件想知道服务在不在（页面显示状态用） */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var alive = true

    /** 正在放的播放器（onDestroy 要收掉，不然会漏音） */
    @Volatile
    private var player: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFY_ID, buildNotification())
        isRunning = true
        // 播放循环跑在自己的线程上：绝不占主线程（不然界面和系统回调都会卡）
        worker.execute { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统杀掉后自动重来：长播期间用户不该管这个
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        runCatching { player?.release() }
        player = null
        worker.shutdownNow()
        isRunning = false
        super.onDestroy()
    }

    // ───────────────────────── 播放循环 ─────────────────────────

    private fun loop() {
        while (alive) {
            val waitMs = try {
                tick()
            } catch (e: Exception) {
                ERROR_BACKOFF_MS
            }
            if (waitMs > 0) sleep(waitMs)
        }
    }

    /** 取一段、念一段；返回下一次轮询前等多久（毫秒）。 */
    private fun tick(): Long {
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        // 身份还没交接（页面一次都没打开过）：等着，别白发请求
        if (key.isBlank()) return IDLE_POLL_MS

        val nextRaw = Http.postJson("$base/api/live/next?player=app", "{}", key, token, 8_000)
            ?: return ERROR_BACKOFF_MS
        val next = try {
            JSONObject(nextRaw)
        } catch (e: Exception) {
            return ERROR_BACKOFF_MS
        }

        // 这台手机被设成"不出声"：服务端没有推进游标，歇一会儿再看
        if (next.optBoolean("skip", false)) return IDLE_POLL_MS
        // 到点自动关播：服务端正在收尾，这里歇着
        if (next.optBoolean("autoClose", false)) return IDLE_POLL_MS
        // 没在播 / 队列为空（服务端会带 error）：歇一会儿
        if (next.has("error")) return IDLE_POLL_MS

        val seg = next.optJSONObject("segment") ?: return IDLE_POLL_MS
        val text = seg.optString("text", "").trim()
        if (text.isEmpty()) return 500L

        val ref = next.optString("ref", "")
        val kind = seg.optString("kind", "host")
        val speed = next.optDouble("speed", 1.0)
        val gain = next.optDouble("gain", 1.0)
        val gapSec = next.optDouble("gapSec", 0.3)

        val audio = ttsUrl(base, text, ref, kind, key, token) ?: return ERROR_BACKOFF_MS
        playIt(audio, speed, gain)
        return (gapSec * 1000).toLong().coerceAtLeast(100L)
    }

    /** 拿这段文字的可播地址（和网页同一个接口、同一套参数）。 */
    private fun ttsUrl(
        base: String,
        text: String,
        ref: String,
        kind: String,
        key: String,
        token: String,
    ): String? {
        val body = JSONObject()
            .put("text", text)
            .put("ref", ref)
            .put("kind", kind)
            .toString()
        val raw = Http.postJson("$base/api/tts", body, key, token, TTS_TIMEOUT_MS) ?: return null
        return try {
            JSONObject(raw).optString("audio", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** 放一段（放完才返回）。语速/音量按服务端给的参数走，和网页那套一致。 */
    private fun playIt(url: String, speed: Double, gain: Double) {
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(url)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ ->
                done.countDown()
                true
            }
            mp.setVolume(
                gain.toFloat().coerceIn(0f, 3f),
                gain.toFloat().coerceIn(0f, 3f),
            )
            mp.prepare()
            mp.start()
            // 语速：安卓 6.0+ 支持 PlaybackParams；个别机型/音频格式不支持时按原速放，不影响出声
            runCatching {
                mp.playbackParams = mp.playbackParams.setSpeed(speed.toFloat().coerceIn(0.5f, 2f))
            }
            done.await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // 放不出来（地址过期/网络抖动）：跳过这一段，别把整条队列卡死在原地
        } finally {
            runCatching { mp.stop() }
            runCatching { mp.release() }
            player = null
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ───────────────────────── 通知 ─────────────────────────

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
}package cc.luxiaofei.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 前台服务：进程保活 + **原生出声**。
 *
 * 为什么要把"念"搬到原生：网页里的 JS 计时器在后台会被系统降频甚至冷冻 ——
 * 一开抖音直播（我们 App 就退到后台了），"网页自己一句一句往下念"就会变慢、甚至停住。
 * 所以播放循环放在原生：只要服务还活着，**抖音在前台、屏幕锁着，它都照念**。
 *
 * 取稿子的接口和网页**完全一样**（/api/live/next → /api/tts），所以音色、话术、
 * 节奏、算力计费全都不变；变的只是"谁来放这一段"。
 *
 * 两个关键约定：
 *   1. 取稿子时带 `?player=app`：服务端据此知道"这是手机原生在取"。如果这台手机被设成
 *      「本机出声：关」（账号设置 appSpeak=false），服务端会回 `{skip:true}` 且**不推进游标** ——
 *      队列是共用的，推进了就等于"把这句吃掉又不念"，另一台设备再也拿不到它。
 *   2. 一次只放一段，放完 + 停顿（gapSec）再取下一段，节奏与网页那套一致。
 *
 * 通知栏那张卡片是安卓对前台服务的要求（顺便也是用户的"我还在播"提示）。
 */
class LiveService : Service() {

    companion object {
        private const val CHANNEL_ID = "dbb-live"
        private const val NOTIFY_ID = 1001

        /** 空闲时多久问一次"有没有在播"（没开播时服务端会很快回，代价很小） */
        private const val IDLE_POLL_MS = 4000L
        /** 出错（网络/接口）后的退避，别把手机和服务器都刷爆 */
        private const val ERROR_BACKOFF_MS = 6000L
        /** 单条 TTS 最多等多久 */
        private const val TTS_TIMEOUT_MS = 25_000
        /** 单条音频最多放多久（兜底，防止播放器卡住不回调） */
        private const val PLAY_TIMEOUT_SEC = 120L

        /** 别的组件想知道服务在不在（页面显示状态用） */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var alive = true

    /** 正在放的播放器（onDestroy 要收掉，不然会漏音） */
    @Volatile
    private var player: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFY_ID, buildNotification())
        isRunning = true
        // 播放循环跑在自己的线程上：绝不占主线程（不然界面和系统回调都会卡）
        worker.execute { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统杀掉后自动重来：长播期间用户不该管这个
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        runCatching { player?.release() }
        player = null
        worker.shutdownNow()
        isRunning = false
        super.onDestroy()
    }

    // ───────────────────────── 播放循环 ─────────────────────────

    private fun loop() {
        while (alive) {
            val waitMs = try {
                tick()
            } catch (e: Exception) {
                ERROR_BACKOFF_MS
            }
            if (waitMs > 0) sleep(waitMs)
        }
    }

    /** 取一段、念一段；返回下一次轮询前等多久（毫秒）。 */
    private fun tick(): Long {
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        // 身份还没交接（页面一次都没打开过）：等着，别白发请求
        if (key.isBlank()) return IDLE_POLL_MS

        val nextRaw = Http.postJson("$base/api/live/next?player=app", "{}", key, token, 8_000)
            ?: return ERROR_BACKOFF_MS
        val next = try {
            JSONObject(nextRaw)
        } catch (e: Exception) {
            return ERROR_BACKOFF_MS
        }

        // 这台手机被设成"不出声"：服务端没有推进游标，歇一会儿再看
        if (next.optBoolean("skip", false)) return IDLE_POLL_MS
        // 到点自动关播：服务端正在收尾，这里歇着
        if (next.optBoolean("autoClose", false)) return IDLE_POLL_MS
        // 没在播 / 队列为空（服务端会带 error）：歇一会儿
        if (next.has("error")) return IDLE_POLL_MS

        val seg = next.optJSONObject("segment") ?: return IDLE_POLL_MS
        val text = seg.optString("text", "").trim()
        if (text.isEmpty()) return 500L

        val ref = next.optString("ref", "")
        val kind = seg.optString("kind", "host")
        val speed = next.optDouble("speed", 1.0)
        val gain = next.optDouble("gain", 1.0)
        val gapSec = next.optDouble("gapSec", 0.3)

        val audio = ttsUrl(base, text, ref, kind, key, token) ?: return ERROR_BACKOFF_MS
        playIt(audio, speed, gain)
        return (gapSec * 1000).toLong().coerceAtLeast(100L)
    }

    /** 拿这段文字的可播地址（和网页同一个接口、同一套参数）。 */
    private fun ttsUrl(
        base: String,
        text: String,
        ref: String,
        kind: String,
        key: String,
        token: String,
    ): String? {
        val body = JSONObject()
            .put("text", text)
            .put("ref", ref)
            .put("kind", kind)
            .toString()
        val raw = Http.postJson("$base/api/tts", body, key, token, TTS_TIMEOUT_MS) ?: return null
        return try {
            JSONObject(raw).optString("audio", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** 放一段（放完才返回）。语速/音量按服务端给的参数走，和网页那套一致。 */
    private fun playIt(url: String, speed: Double, gain: Double) {
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(url)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ ->
                done.countDown()
                true
            }
            mp.setVolume(
                gain.toFloat().coerceIn(0f, 3f),
                gain.toFloat().coerceIn(0f, 3f),
            )
            mp.prepare()
            mp.start()
            // 语速：安卓 6.0+ 支持 PlaybackParams；个别机型/音频格式不支持时按原速放，不影响出声
            runCatching {
                mp.playbackParams = mp.playbackParams.setSpeed(speed.toFloat().coerceIn(0.5f, 2f))
            }
            done.await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // 放不出来（地址过期/网络抖动）：跳过这一段，别把整条队列卡死在原地
        } finally {
            runCatching { mp.stop() }
            runCatching { mp.release() }
            player = null
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ───────────────────────── 通知 ─────────────────────────

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
