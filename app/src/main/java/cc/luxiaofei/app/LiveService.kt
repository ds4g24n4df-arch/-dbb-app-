package cc.luxiaofei.app

/*
 * 注意用别名：有些依赖里也有个 Notification，直接 import 会和它撞名
 * （CI 报过 "Conflicting import: imported name 'Notification' is ambiguous"）。
 * 加别名之后本文件只用 AndroidNotification，歧义就没了。
 */
import android.app.Notification as AndroidNotification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 前台服务：进程保活 + **原生出声**（稳定、连续）。
 *
 * 为什么要把"念"搬到原生：网页里的 JS 计时器在后台会被系统降频甚至冷冻 ——
 * 一开抖音直播（我们 App 就退到后台了），"网页自己一句一句往下念"就会变慢、甚至停住。
 */
class LiveService : Service() {

    companion object {
        private const val CHANNEL_ID = "dbb-live"
        private const val NOTIFY_ID = 1001

        private const val IDLE_POLL_MS = 3000L
        private const val ERROR_BACKOFF_MS = 5000L
        private const val TTS_TIMEOUT_MS = 25_000
        private const val PREFETCH_WAIT_SEC = 12L
        private const val PLAY_TIMEOUT_SEC = 180L
        private const val MIN_GAP_MS = 120L
        private const val MAX_GAP_MS = 3000L

        @Volatile
        var isRunning: Boolean = false
            private set
    }

       private data class Segment(
        val ref: String,
        val speed: Double,
        val gain: Double,
        val gapMs: Long,
        val audio: String,
    )

    private class Fetched(val segment: Segment?, val waitMs: Long)

    private val worker = Executors.newSingleThreadExecutor()
    private val prefetcher = Executors.newSingleThreadExecutor()

    @Volatile
    private var alive = true

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var lastClockAskAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFY_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFY_ID, buildNotification())
            }
        } catch (e: Throwable) {
            runCatching { startForeground(NOTIFY_ID, buildNotification()) }
        }
        isRunning = true
        diag("原生播放服务已启动 v" + BuildConfig.VERSION_NAME)
        worker.execute { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        runCatching { player?.release() }
        player = null
        prefetcher.shutdownNow()
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
        // 0) 先把"弹幕回答"播掉：主播这句念完 → 马上答公屏
        var reply: Segment? = takeReadyReply()
        if (reply == null) reply = fetchPendingOne()
        if (reply != null) {
            // 播回答的这几秒里，先把下一句话术取回来合成好 ——
            // 不然回答念完还要现合成 2~3 秒，中间又是一段静音
            val prefetched = startPrefetch()
            playIt(reply.audio, reply.speed, reply.gain)
            collectPrefetch(prefetched)
            startReplyPrefetch()
        }

        // 报时由原生定时去问，服务端按 pinClock 卡间隔
        askClockIfDue()

        // 1) 现在要播的这段：优先用预取好的
        val ready = takeReady()
        val seg: Segment
        if (ready != null) {
            seg = ready
        } else {
            val first = fetchNext()
            val got = first.segment ?: return first.waitMs
            seg = got
        }

        // 2) 开播之前先把下一段、以及"下一条弹幕回答"都交出去准备
        val pending = startPrefetch()
        startReplyPrefetch()

        // 3) 放
        playIt(seg.audio, seg.speed, seg.gain)

        // 4) 把预取结果收回来
        collectPrefetch(pending)

        return seg.gapMs
    }
    @Volatile
    private var nextReady: Segment? = null

    private fun takeReady(): Segment? {
        val s = nextReady
        nextReady = null
        return s
    }

    private fun startPrefetch(): Future<Fetched>? {
        if (!alive) return null
        return try {
            prefetcher.submit<Fetched> { fetchNext() }
        } catch (e: Exception) {
            null
        }
    }
    /**
     * 提前把「下一条弹幕回答」准备成可直接播的本地文件。
     *
     * 以前是这句话念完才去取回答 → 现合成 2~3 秒 + 现下载 1 秒，
     * 于是回答开口前有一大段静音。现在趁念话术的时间就合成+下载好，
     * 这句一完直接开播（静音约 0.2 秒）。
     */
    @Volatile
    private var readyReply: Segment? = null

    @Volatile
    private var replyPrefetching = false
        /** 后台常驻：每 1.5 秒问一次待念，取到就下载到本地放进"已备好"。 */
    private fun startReplyLoop() {
        Thread {
            while (alive) {
                try {
                    if (readyReply == null && !replyPrefetching) startReplyPrefetch()
                } catch (e: Exception) {
                }
                sleep(1500)
            }
        }.start()
    }

    
    private fun startReplyPrefetch() {
        if (!alive || replyPrefetching || readyReply != null) return
        replyPrefetching = true
        try {
            prefetcher.submit {
                try {
                    val s = fetchPendingOne()
                    if (s != null) {
                        val file = downloadAudio(s.audio)
                        if (file != null) readyReply = s.copy(audio = file.absolutePath)
                    }
                } catch (e: Exception) {
                    // 预热失败不影响主线：真要播时还会再取一次
                } finally {
                    replyPrefetching = false
                }
            }
        } catch (e: Exception) {
            replyPrefetching = false
        }
    }

    private fun takeReadyReply(): Segment? {
        val s = readyReply
        readyReply = null
        return s
    }

    private fun collectPrefetch(pending: Future<Fetched>?) {
        if (pending == null) return
        try {
            val got = pending.get(PREFETCH_WAIT_SEC, TimeUnit.SECONDS)
            if (got.segment != null) nextReady = got.segment
        } catch (e: Exception) {
            // 预取没赶上：下一轮重新取
        }
    }

    // ───────────────────────── 取稿 ─────────────────────────

    private fun fetchNext(): Fetched {
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        if (key.isBlank()) return Fetched(null, IDLE_POLL_MS)

        val nextRaw = Http.postJson("$base/api/live/next?player=app", "{}", key, token, 8_000)
            ?: return Fetched(null, ERROR_BACKOFF_MS)
        val next = try {
            JSONObject(nextRaw)
        } catch (e: Exception) {
            return Fetched(null, ERROR_BACKOFF_MS)
        }

        if (next.optBoolean("skip", false)) return Fetched(null, IDLE_POLL_MS)
        if (next.optBoolean("autoClose", false)) return Fetched(null, IDLE_POLL_MS)
        if (next.has("error")) return Fetched(null, IDLE_POLL_MS)

        val seg = next.optJSONObject("segment") ?: return Fetched(null, IDLE_POLL_MS)
        val text = seg.optString("text", "").trim()
        if (text.isEmpty()) return Fetched(null, 300L)

        val ref = next.optString("ref", "")
        val kind = seg.optString("kind", "host")
        val speed = next.optDouble("speed", 1.0)
        val gain = next.optDouble("gain", 1.0)
        val gapMs = (next.optDouble("gapSec", 0.3) * 1000).toLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS)

        val audio = ttsUrl(base, text, ref, kind, key, token)
        if (audio == null) {
            diag("合成失败（这段放不出来）：" + text.take(30))
            return Fetched(null, ERROR_BACKOFF_MS)
        }
        return Fetched(Segment(ref, speed, gain, gapMs, audio), 0L)
    }

    /** 每 ~60 秒问一次服务端"该报时了吗"。 */
    private fun askClockIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastClockAskAt < 60_000L) return
        lastClockAskAt = now
        runCatching {
            Http.postJson(
                Prefs.baseUrl(this) + "/api/live/event",
                "{\"kind\":\"clock\"}",
                Prefs.deviceKey(this),
                Prefs.authToken(this),
                8_000,
            )
        }
    }

    /**
     * 取一条"待念的互动"（弹幕回答 / 进人 / 关注 / 点赞）。
     *
     * 取不到时会把原因报上去（"0 条" / "失败"），现场就能看出是
     * **服务端不给** 还是 **取到了没播**。
     */
    private fun fetchPendingOne(): Segment? {
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        if (key.isBlank()) return null

        val raw = Http.postJson("$base/api/live/pending", "{}", key, token, 8_000)
        if (raw == null) {
            diag("取回答失败（网络或被拒）")
            return null
        }
        val items = try {
            JSONObject(raw).optJSONArray("items")
        } catch (e: Exception) {
            null
        } ?: return null
        if (items.length() == 0) {
            diag("取回答 → 0 条")
            return null
        }
        diag("取回答 → " + items.length() + " 条")

        val it = items.optJSONObject(0) ?: return null
        val text = it.optString("text", "").trim()
        if (text.isEmpty()) return null
        val ref = it.optString("ref", "")
        val kind = it.optString("kind", "reply")
        val gain = it.optDouble("gain", 1.0)
        val audio = ttsUrl(base, text, ref, kind, key, token)
        if (audio == null) {
            diag("合成失败（这条放不出来）：" + text.take(30))
            return null
        }
        return Segment(ref, 1.0, gain, MIN_GAP_MS, audio)
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

    // ───────────────────────── 播放 ─────────────────────────

    /** 放一段（放完才返回）。先下载到本地再放，失败会写进互动日志。 */
    private fun playIt(url: String, speed: Double, gain: Double) {
        val mp = MediaPlayer()
                // 申请音频焦点：不然抖音一直占着音频，我们一放音就被掐掉
        // （现场表现：回答刚开口就停，切回 App 才继续说）
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var focus: AudioFocusRequest? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            am.requestAudioFocus(focus)
        }
        player = mp
        var lock: PowerManager.WakeLock? = null
        try {
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dbb:play")
                .apply { acquire(PLAY_TIMEOUT_SEC * 1000L) }

            val file = downloadAudio(url)
            if (file == null) {
                diag("音频下载失败：" + url.takeLast(40))
                return
            }

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            val done = CountDownLatch(1)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, what, extra ->
                diag("播放失败：what=" + what + " extra=" + extra)
                done.countDown()
                true
            }
            mp.setVolume(gain.toFloat().coerceIn(0f, 3f), gain.toFloat().coerceIn(0f, 3f))
            mp.prepare()
            mp.start()
            runCatching {
                mp.playbackParams = mp.playbackParams.setSpeed(speed.toFloat().coerceIn(0.5f, 2f))
            }
            done.await(PLAY_TIMEOUT_SEC, TimeUnit.SECONDS)
            runCatching { file.delete() }
        } catch (e: Throwable) {
            diag("播放异常：" + e.javaClass.simpleName + " " + (e.message ?: ""))
        } finally {
                        if (focus != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                am.abandonAudioFocusRequest(focus)
            }
            runCatching { lock?.release() }
            runCatching { mp.stop() }
            runCatching { mp.release() }
            player = null
        }
    }

    /** 把音频下载到缓存目录再放：云端地址是 302 跳转，直连播放在部分机型上会失败。 */
        private fun downloadAudio(url: String): File? {
        if (url.startsWith("/")) {
            val local = File(url)
            return if (local.exists() && local.length() > 1000) local else null
        }
        var conn: java.net.HttpURLConnection? = null
        return try {
            val f = File(cacheDir, "seg-" + System.nanoTime() + ".mp3")
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn = c
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
            if (f.length() < 1000) null else f
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
        if (now - lastDiagAt < 20_000L) return
        lastDiagAt = now
        try {
            val body = "{\"events\":[{\"kind\":\"bullet\",\"nickname\":\"原生自检\",\"question\":\"" +
                msg.take(160).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}]}"
            Http.postJson(
                Prefs.baseUrl(this) + "/api/live/inbound",
                body,
                Prefs.deviceKey(this),
                Prefs.authToken(this),
                5000,
            )
        } catch (_: Exception) {
        }
    }

    private var lastDiagAt = 0L

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
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.service_channel_desc)
            },
        )
    }

    private fun buildNotification(): AndroidNotification {
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
