package cc.luxiaofei.app

import android.app.Notification as AndroidNotification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 * 前台服务：进程保活 + 原生出声（稳定、连续）。
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

        /** 播放中"瞄一眼有没有观众提问"的间隔 */
        private const val PENDING_PROBE_MS = 1500L
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

    /** 语音包预热线程（只做下载，不参与播放） */
    private val packWarmer = Executors.newSingleThreadExecutor()

    @Volatile
    private var alive = true

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var lastClockAskAt = 0L

    /** 这台手机该不该由原生出声（账号设置 appSpeak）；默认 true */
    @Volatile
    private var nativeSpeaking = true

    /** 上次问"该不该原生出声"的时间：60 秒问一次 */
    @Volatile
    private var lastSpeakAskAt = 0L

    /** 本场是否已经开始把语音包下载到机内 */
    @Volatile
    private var packWarmed = false

    /** 抢播槽位：播放中探到的观众提问（单独放，别被预取结果挤掉） */
    @Volatile
    private var urgentReady: Segment? = null

    @Volatile
    private var nextReady: Segment? = null

    @Volatile
    private var readyReply: Segment? = null

    @Volatile
    private var replyPrefetching = false

    private var lastDiagAt = 0L

    private fun takeUrgent(): Segment? {
        val s = urgentReady
        urgentReady = null
        return s
    }

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
        startReplyLoop()
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
        packWarmer.shutdownNow()
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
        // 先确认这台手机该不该由原生出声（用户关掉"本机出声"就彻底不念，
        // 交给网页出声，避免两边同时念）
        refreshSpeakFlag()
        if (!nativeSpeaking) return IDLE_POLL_MS

        // 播放中被掐断、存进抢播槽位的那条观众提问：优先念掉
        val urgent = takeUrgent()
        if (urgent != null) {
            playIt(urgent.audio, urgent.speed, urgent.gain, allowInterrupt = false)
            return 200L
        }

        // 0) 先把"弹幕回答"播掉：主播这句念完 → 马上答公屏
        var reply: Segment? = takeReadyReply()
        if (reply == null) reply = fetchPendingOne()
        if (reply != null) {
            // 播回答的这几秒里，先把下一句话术取回来合成好 ——
            // 不然回答念完还要现合成 2~3 秒，中间又是一段静音
            val prefetched = startPrefetch()
            playIt(reply.audio, reply.speed, reply.gain, allowInterrupt = false)
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

        // 3) 放：口播稿**不打断**（这句一定念完，念完立刻接下一条弹幕回答）
        startPackWarmOnce()
        playIt(seg.audio, seg.speed, seg.gain, allowInterrupt = false)
        // 4) 把预取结果收回来
        collectPrefetch(pending)
        // 这一句一定念完，回到循环第一件事就是念待答的弹幕回答
        return seg.gapMs
    }
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

    /** 后台常驻：每 1.5 秒问一次待念，取到就下载到本地放进"已备好"。 */
    private fun startReplyLoop() {
        Thread {
            while (alive) {
                try {
                    if (readyReply == null && !replyPrefetching) startReplyPrefetch()
                } catch (e: Exception) {
                    // 忽略：下一轮再来
                }
                sleep(1500)
            }
        }.start()
    }

    /**
     * 提前把「下一条弹幕回答」准备成可直接播的本地文件。
     */
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
     */
    private fun fetchPendingOne(urgentOnly: Boolean = false): Segment? {
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        if (key.isBlank()) return null

        // single：只要一条（原生一次只念一条，多取会被服务端标"已念"却没人出声）
        // urgent：只问弹幕/回复（播放中的抢播探针专用，别把点赞/关注/报时顺手取走）
        val body = if (urgentOnly) "{\"urgent\":true}" else "{\"single\":true}"
        val raw = Http.postJson("$base/api/live/pending", body, key, token, 8_000)
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
            if (!urgentOnly) diag("取回答 → 0 条")
            return null
        }
        if (!urgentOnly) diag("取回答 → " + items.length() + " 条")

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
        // 本地优先：合成过一次的句子，之后直接播机内文件（路径以 "/" 开头，
        // playIt 里走的是本地分支，不联网）—— 这就是"开播 0 延迟"。
        val cacheKey = VoiceCache.key(text, ref, kind)
        VoiceCache.get(this, cacheKey)?.let { return it.absolutePath }

        val body = JSONObject()
            .put("text", text)
            .put("ref", ref)
            .put("kind", kind)
            .toString()
        val raw = Http.postJson("$base/api/tts", body, key, token, TTS_TIMEOUT_MS) ?: return null
        val remote = try {
            JSONObject(raw).optString("audio", "").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } ?: return null

        // 先落到机内再播；下载失败就退回云端地址（宁可这一句吃网络，也别没声音）
        val dst = VoiceCache.file(this, cacheKey)
        return if (Http.download(remote, dst, TTS_TIMEOUT_MS)) dst.absolutePath else remote
    }

    /**
     * 问一次服务端：这台手机该不该由原生出声（/api/live 返回的 appSpeak）。
     * 60 秒问一次就够 —— 用户改完开关，最多一分钟生效。
     */
    private fun refreshSpeakFlag() {
        val now = System.currentTimeMillis()
        if (now - lastSpeakAskAt < 60_000L) return
        lastSpeakAskAt = now
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        if (key.isBlank()) return
        val raw = Http.getJson("$base/api/live", key, token, 8_000) ?: return
        nativeSpeaking = try {
            JSONObject(raw).optBoolean("appSpeak", true)
        } catch (e: Exception) {
            true
        }
        if (nativeSpeaking) startPackWarmOnce()
    }

    /** 语音包只预热一次（开播那几分钟把常用句子落到机内，之后全走本地）。 */
    private fun startPackWarmOnce() {
        if (packWarmed) return
        packWarmed = true
        runCatching { packWarmer.execute { warmVoicePack() } }
    }

    /**
     * 把服务端给的语音包清单（开场白/间隔话术/报时）逐条合成并落到机内。
     * 之后这些句子不用再联网合成，开播瞬间就能出声。
     */
    private fun warmVoicePack() {
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        if (key.isBlank()) return
        val raw = Http.getJson("$base/api/live/voice-pack", key, token, 20_000) ?: return
        val items = try {
            JSONObject(raw).optJSONArray("items")
        } catch (e: Exception) {
            null
        } ?: return
        var done = 0
        for (i in 0 until items.length()) {
            if (!alive) return
            val it = items.optJSONObject(i) ?: continue
            val text = it.optString("text", "").trim()
            if (text.isEmpty()) continue
            val ref = it.optString("ref", "")
            val kind = it.optString("kind", "idle")
            if (ttsUrl(base, text, ref, kind, key, token) != null) done++
        }
        diag("语音包预热完成：" + done + "/" + items.length())
    }

    // ───────────────────────── 播放 ─────────────────────────

    /** 放一段（放完才返回）。返回 true = 被观众提问掐断。 */
    private fun playIt(
        url: String,
        speed: Double,
        gain: Double,
        allowInterrupt: Boolean = true,
    ): Boolean {
        val mp = MediaPlayer()
        // 申请音频焦点：不然抖音一直占着音频，我们一放音就被掐掉
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
        var interrupted = false
        var lock: PowerManager.WakeLock? = null
        try {
            lock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dbb:play")
                .apply { acquire(PLAY_TIMEOUT_SEC * 1000L) }

            val file = downloadAudio(url)
            if (file == null) {
                diag("音频下载失败：" + url.takeLast(40))
                return false
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
            // 边播边瞄：每 1.5 秒问一次"有没有观众提问"。有就掐掉这一句、存到抢播槽位，
            // 由 tick() 立刻念出来（urgent 只问弹幕，点赞/关注/报时不会被误取走）。
            // 另外挂总时长兜底：个别机型 onCompletion 不回调，这里到点自己收。
            val deadline = System.currentTimeMillis() + PLAY_TIMEOUT_SEC * 1000
            while (!done.await(PENDING_PROBE_MS, TimeUnit.MILLISECONDS)) {
                if (!alive || System.currentTimeMillis() > deadline) break
                if (!allowInterrupt) continue
                val urgent = fetchPendingOne(urgentOnly = true)
                if (urgent != null) {
                    urgentReady = urgent
                    interrupted = true
                    break
                }
            }
            if (interrupted) runCatching { mp.stop() }
            // 只删"这一轮临时下载"的文件；机内缓存的（url 以 / 开头）留着下次直接播
            if (!url.startsWith("/")) runCatching { file.delete() }
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
        return interrupted
    }

    /** 把音频下载到缓存目录再放：云端地址是 302 跳转，直连播放在部分机型上会失败。 */
    private fun downloadAudio(url: String): File? {
        if (url.startsWith("/")) {
            val local = File(url)
            return if (local.exists() && local.length() > 1000) local else null
        }
        var target = url
        var conn: java.net.HttpURLConnection? = null
        return try {
            val f = File(cacheDir, "seg-" + System.nanoTime() + ".mp3")
            // 音频地址是 302 跳转，HttpURLConnection 跨域/跨协议不会自动跟 —— 手动跟 3 次
            var hops = 0
            while (hops < 3) {
                val c = java.net.URL(target).openConnection() as java.net.HttpURLConnection
                conn = c
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    if (loc.isNullOrBlank()) return null
                    target = java.net.URL(java.net.URL(target), loc).toString()
                    hops++
                    continue
                }
                if (code !in 200..299) return null
                c.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                return if (f.length() < 1000) null else f
            }
            null
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun diag(msg: String) {
        val now = System.currentTimeMillis()
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
