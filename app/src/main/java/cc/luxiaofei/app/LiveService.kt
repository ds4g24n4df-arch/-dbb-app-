package cc.luxiaofei.app

/*
 * 注意用别名：有些依赖里也有个 Notification，直接 import 会和它撞名
 * （CI 报过 "Conflicting import: imported name 'Notification' is ambiguous"）。
 * 加别名之后本文件只用 AndroidNotification，歧义就没了。
 */
import android.app.Notification as AndroidNotification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 前台服务：进程保活 + **原生出声**（稳定、连续）。
 *
 * 为什么要把"念"搬到原生：网页里的 JS 计时器在后台会被系统降频甚至冷冻 ——
 * 一开抖音直播（我们 App 就退到后台了），"网页自己一句一句往下念"就会变慢、甚至停住。
 * 所以播放循环放在原生：只要服务还活着，**抖音在前台、屏幕锁着，它都照念**。
 *
 * 取稿子的接口和网页完全一样（/api/live/next → /api/tts），所以音色、话术、节奏、
 * 算力计费全都不变；变的只是"谁来放这一段"。
 *
 * **连续**是怎么做到的（老板的硬要求：不能一句一顿）：
 *   播当前这句的同时，另一条线程已经去把**下一句**取回来 + 合成好了（见 prefetch）。
 *   所以正常情况下两句之间只留 gapSec 的换气停顿，不用等合成（单句冷合成要好几秒）。
 *
 * 三个关键约定：
 *   1. 取稿子时带 `?player=app`：服务端据此知道"这是手机原生在取"。如果这台手机被设成
 *      「本机出声：关」（账号设置 appSpeak=false），服务端会回 `{skip:true}` 且**不推进游标** ——
 *      队列是共用的，推进了就等于"把这句吃掉又不念"，另一台设备再也拿不到它。
 *   2. 取回来的段先放进 nextReady，再开始播；播放前先把下一段交出去合成。
 *   3. 任何一步出错都只退避重试，绝不退出循环 —— 直播中途"停了不念"是最严重的事故。
 *
 * 通知栏那张卡片是安卓对前台服务的要求（顺便也是用户的"我还在播"提示）。
 */
class LiveService : Service() {

    companion object {
        private const val CHANNEL_ID = "dbb-live"
        private const val NOTIFY_ID = 1001

        /** 空闲时多久问一次"有没有在播"（没开播时服务端会很快回，代价很小） */
        private const val IDLE_POLL_MS = 3000L
        /** 出错（网络/接口）后的退避，别把手机和服务器都刷爆 */
        private const val ERROR_BACKOFF_MS = 5000L
        /** 单条 TTS 最多等多久 */
        private const val TTS_TIMEOUT_MS = 25_000
        /** 等预取结果最多等多久（等不到就按空档走，下一轮重新取） */
        private const val PREFETCH_WAIT_SEC = 12L
        /** 单条音频最多放多久（兜底，防止播放器卡住不回调） */
        private const val PLAY_TIMEOUT_SEC = 180L
        /** 两次播放之间最短/最长的换气停顿（服务端给的 gapSec 也会被夹在这个区间里） */
        private const val MIN_GAP_MS = 120L
        private const val MAX_GAP_MS = 3000L

        /** 别的组件想知道服务在不在（页面显示状态用） */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    /** 一段可以马上播的内容：文本、音色、语速/音量、换气时长、可播地址。 */
    private class Segment(
        val ref: String,
        val speed: Double,
        val gain: Double,
        val gapMs: Long,
        val audio: String,
    )

    /** 一次取稿的结果：要么拿到可播的一段，要么说明"该等多久再试"。 */
    private class Fetched(val segment: Segment?, val waitMs: Long)

    private val worker = Executors.newSingleThreadExecutor()

    /** 预取专用线程：播放时它同时在把下一段取回来合成 */
    private val prefetcher = Executors.newSingleThreadExecutor()

    @Volatile
    private var alive = true

    /** 正在放的播放器（onDestroy 要收掉，不然会漏音） */
    @Volatile
    private var player: MediaPlayer? = null

    /** 上次"问服务端该不该报时"的时间（见 askClockIfDue） */
    @Volatile
    private var lastClockAskAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        /**
         * 安卓 14+ 的硬规矩：清单里声明了 foregroundServiceType="mediaPlayback"，
         * startForeground 就**必须把类型一起传进去** —— 不传会直接抛
         * MissingForegroundServiceTypeException，服务当场死掉。
         *
         * 现场表现就是：**「本机出声」开着，却一点声音都没有**（服务没起来，
         * 网页那边又按规矩让位了，于是两边都不出声）。
         */
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
            // 兜底：万一某个系统不认这个类型，也别让服务死掉（退回不带类型的老写法）
            runCatching { startForeground(NOTIFY_ID, buildNotification()) }
        }
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
        /**
         * 0) **先把"弹幕回答"播掉** —— 这是现场"话术有声音、弹幕回答没声音"的根因。
         *
         * 弹幕回答（以及进人/关注/点赞的播报）跟口播稿走的是**两条路**：
         *   口播稿：/api/live/next?player=app  ← 原生取（就是本文件这条）
         *   弹幕回答：/api/live/pending        ← 以前**只有网页**取
         * 而「本机出声：开」时网页那一路已经让位了，于是弹幕回答**没人取**，
         * 服务端记着"念了"，直播间里却一声都没有。
         * 这里补上：每一轮先问一句有没有待念的互动，有就立刻插播，然后再接着念口播稿。
         */
        val reply = fetchPendingOne()
        if (reply != null) {
            playIt(reply.audio, reply.speed, reply.gain)
            return 200L
        }

        /**
         * 报时：**由原生这边定时去问**，真正的间隔由服务端按 pinClock 卡。
         *
         * 为什么不能只靠网页那一路的定时器：报时原来是网页里的一个 setInterval ——
         * 但用户开播后就把 App 切到抖音了，**WebView 在后台会被系统冻住**，
         * 定时器根本不跑，所以"昨天一次报时都没有"（现场反馈）。
         * 原生这边的循环不受冻结影响，问一句"该报时了吗"最靠谱。
         * 服务端自己会按间隔拦（回 triggered:false + 还需多少秒），所以问勤一点也没关系。
         */
        askClockIfDue()

        // 1) 现在要播的这段：优先用预取好的（这就是"连续"的关键）
        val ready = takeReady()
        val seg: Segment
        if (ready != null) {
            seg = ready
        } else {
            val first = fetchNext()
            val got = first.segment ?: return first.waitMs
            seg = got
        }

        // 2) 开播之前先把**下一段**交出去合成：等这首放完，下一首往往已经好了
        val pending = startPrefetch()

        // 3) 放
        playIt(seg.audio, seg.speed, seg.gain)

        // 4) 把预取结果收回来（此时多半已完成）
        collectPrefetch(pending)

        return seg.gapMs
    }

    // ───────────────────────── 预取 ─────────────────────────

    @Volatile
    private var nextReady: Segment? = null

    private fun takeReady(): Segment? {
        val s = nextReady
        nextReady = null
        return s
    }

    /** 起一条后台线程去取下一段；返回这个任务，稍后用 collectPrefetch 收。 */
    private fun startPrefetch(): Future<Fetched>? {
        if (!alive) return null
        return try {
            prefetcher.submit<Fetched> { fetchNext() }
        } catch (e: Exception) {
            null
        }
    }

    private fun collectPrefetch(pending: Future<Fetched>?) {
        if (pending == null) return
        try {
            val got = pending.get(PREFETCH_WAIT_SEC, TimeUnit.SECONDS)
            if (got.segment != null) nextReady = got.segment
        } catch (e: Exception) {
            // 预取没赶上：不管它，下一轮 tick 会重新取（只是这段会多等一次合成）
        }
    }

    // ───────────────────────── 取稿 ─────────────────────────

    /** 取下一段并把语音合成好（这一步就是那条"要好几秒"的活，所以放到预取线程里）。 */
    private fun fetchNext(): Fetched {
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        // 身份还没交接（App 里一次页面都没打开过）：等着，别白发请求
        if (key.isBlank()) return Fetched(null, IDLE_POLL_MS)

        val nextRaw = Http.postJson("$base/api/live/next?player=app", "{}", key, token, 8_000)
            ?: return Fetched(null, ERROR_BACKOFF_MS)
        val next = try {
            JSONObject(nextRaw)
        } catch (e: Exception) {
            return Fetched(null, ERROR_BACKOFF_MS)
        }

        // 这台手机被设成"不出声"：服务端没有推进游标，歇一会儿再看
        if (next.optBoolean("skip", false)) return Fetched(null, IDLE_POLL_MS)
        // 到点自动关播：服务端正在收尾，这里歇着
        if (next.optBoolean("autoClose", false)) return Fetched(null, IDLE_POLL_MS)
        // 没在播 / 队列为空（服务端会带 error）：歇一会儿
        if (next.has("error")) return Fetched(null, IDLE_POLL_MS)

        val seg = next.optJSONObject("segment") ?: return Fetched(null, IDLE_POLL_MS)
        val text = seg.optString("text", "").trim()
        if (text.isEmpty()) return Fetched(null, 300L)

        val ref = next.optString("ref", "")
        val kind = seg.optString("kind", "host")
        val speed = next.optDouble("speed", 1.0)
        val gain = next.optDouble("gain", 1.0)
        val gapMs = (next.optDouble("gapSec", 0.3) * 1000).toLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS)

        val audio = ttsUrl(base, text, ref, kind, key, token) ?: return Fetched(null, ERROR_BACKOFF_MS)
        return Fetched(Segment(ref, speed, gain, gapMs, audio), 0L)
    }

    /** 每 ~60 秒问一次服务端"该报时了吗"；念什么、隔多久，全由服务端按配置决定。 */
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
     * 接口和网页完全一样：POST /api/live/pending（取走即标记，不会两边念两遍）。
     * 取到第一条就把它的音频合成好返回；没有就返回 null，继续念口播稿。
     */
    private fun fetchPendingOne(): Segment? {
        val base = Prefs.baseUrl(this).trimEnd('/')
        val key = Prefs.deviceKey(this)
        val token = Prefs.authToken(this)
        if (key.isBlank()) return null

        val raw = Http.postJson("$base/api/live/pending", "{}", key, token, 8_000) ?: return null
        val items = try {
            JSONObject(raw).optJSONArray("items")
        } catch (e: Exception) {
            null
        } ?: return null
        if (items.length() == 0) return null

        val it = items.optJSONObject(0) ?: return null
        val text = it.optString("text", "").trim()
        if (text.isEmpty()) return null
        val ref = it.optString("ref", "")
        val kind = it.optString("kind", "reply")
        val gain = it.optDouble("gain", 1.0)
        // 弹幕回答插播时换气短一点：观众等着答案，别拖
        val audio = ttsUrl(base, text, ref, kind, key, token) ?: return null
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

    private fun buildNotification(): AndroidNotification {
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
