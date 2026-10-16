package cc.luxiaofei.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

/**
 * 读屏采集：把抖音公屏上**本来就显示着**的字读出来，按同一条入站接口推给服务端。
 *
 * 为什么用读屏：抖音的公屏没在直播链接里，要拿只有官方开放平台授权（要企业资质）
 * 或者逆向它的私有长连接（随时被禁、不合规）。读屏走的是第三条：读你屏幕上自己的字 ——
 * 不碰账号、不调它的接口、不逆向，代价是**抖音改版可能让它失效**，得跟着调。
 *
 * 采集到的事件推给线上同一个入站接口（/api/live/inbound），所以下游（相关性判定、
 * 违禁词、额度、宝子、答案缓存、弹幕优先插播）和网页手动喂进来的完全一样 ——
 * 规则只有一份。
 *
 * 两个关键设计：
 *   1. **去重靠"见过就记一笔"**：公屏是"滚动列表"，同一条会一直挂在屏幕上，
 *      靠节点变化事件反复触发。所以按文本记一份"最近见过"的集合，只推新的。
 *      服务端那边还有一层按 msgId 去重，但这里不发 msgId：观众重复问同一句
 *      （"多少钱"）是常事，不能当成重复消息丢掉。
 *   2. **扫窗口要限深限量**：无障碍回调跑在系统线程上，扫太久会连累整个手机发卡。
 */
class DanmuAccessibilityService : AccessibilityService() {

    companion object {
        /** 页面/服务想知道"读屏开着没有" */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** 抖音系包名（无障碍配置里已经限定，这里再兜一层，防止别的事件漏进来） */
        private val TARGETS = setOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite")

        /** 一次扫窗口最多看多少个节点：够覆盖公屏，又不会卡 */
        private const val MAX_NODES = 600
        /** 一次最多往里推几条：一批推多了会挤掉正在念的口播稿 */
        private const val MAX_EVENTS_PER_POST = 5
        /** 文本多短才算"公屏那一行的字"（太长的多半是公告/标题） */
        private const val MAX_TEXT_LEN = 60
        /** 两次扫窗口的最小间隔，公屏刷得再快也不用人眼跟不上的频率去扫 */
        private const val MIN_INTERVAL_MS = 400L
    }

    private val pool = Executors.newSingleThreadExecutor()

    /** 最近见过的文本 → 时间。公屏是滚动列表，同一条会反复出现在节点里。 */
    private val seen = HashMap<String, Long>()
    private var lastScanAt = 0L

    /** 自检上报的节流时间（诊断用，见 diag()） */
    private var lastDiagAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onDestroy() {
        isRunning = false
        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 系统中断（比如别的无障碍服务接管）不需要处理：下次事件来了照常工作
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in TARGETS) return
        if (!Prefs.collectEnabled(this) || !Prefs.hasIdentity(this)) {
            pool.execute {
                diag("抖音事件被开关挡住：采集开关=" + Prefs.collectEnabled(this) + "，身份=" + Prefs.hasIdentity(this))
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastScanAt < MIN_INTERVAL_MS) return
        lastScanAt = now

        val root = rootInActiveWindow
        if (root == null) {
            pool.execute { diag("抖音事件到了，但拿不到窗口内容（root=null）") }
            return
        }
        // 扫窗口、发请求都不在系统回调线程上做（不然手机会卡）
        pool.execute { scanAndPush(root) }
    }

    /** 扫一遍窗口，挑出"新出现的公屏文字"，成批发给入站接口。 */
    private fun scanAndPush(root: AccessibilityNodeInfo) {
        val texts = LinkedHashSet<String>()
        try {
            collect(root, 0, texts, intArrayOf(0))
        } catch (_: Exception) {
            return
        }

        val now = System.currentTimeMillis()
        // 清理 5 分钟没再出现过的记录，集合别无限长
        seen.entries.removeAll { now - it.value > 5 * 60 * 1000 }

        val fresh = texts.mapNotNull { raw ->
            val text = raw.trim().replace('\n', ' ').replace(Regex("\\s+"), " ")
            if (text.isEmpty() || text.length > MAX_TEXT_LEN) return@mapNotNull null
            val kind = kindOf(text) ?: return@mapNotNull null
            if (seen.containsKey(text)) return@mapNotNull null
            seen[text] = now
            event(text, kind)
        }.take(MAX_EVENTS_PER_POST)

        // 自检：把"看到几行字、认出几行 + 样例"推到服务端（每 30 秒最多一条）。
        // 诊断完这段可以删掉。
        diag("看到 " + texts.size + " 行字，认出 " + fresh.size + " 行｜样例：" +
            texts.take(10).map { it.take(20) }.joinToString(" / "))

        if (fresh.isEmpty()) return

        val json = buildString {
            append("{\"events\":[")
            fresh.forEachIndexed { i, ev ->
                if (i > 0) append(',')
                append("{\"kind\":\"").append(ev.kind).append("\",")
                append("\"nickname\":\"").append(esc(ev.nickname)).append("\",")
                append("\"question\":\"").append(esc(ev.question)).append("\"}")
            }
            append("]}")
        }

        val base = Prefs.baseUrl(this)
        Http.postJson(
            "$base/api/live/inbound",
            json,
            Prefs.deviceKey(this),
            Prefs.authToken(this),
            // 短超时：读屏是"顺带做的事"，网络慢不该压着后面的扫描
            5000,
        )
    }

    /**
     * 自检上报：只用于排查"读屏到底读到了什么"。
     * 节流 30 秒，最多带一小段样例文字，推到同一个入站接口，界面上能直接看到。
     */
    private fun diag(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastDiagAt < 30_000L) return
        lastDiagAt = now
        val body = "{\"events\":[{\"kind\":\"bullet\",\"nickname\":\"读屏自检\",\"question\":\"" +
            esc(msg.take(180)) + "\"}]}"
        try {
            Http.postJson(
                Prefs.baseUrl(this) + "/api/live/inbound",
                body,
                Prefs.deviceKey(this),
                Prefs.authToken(this),
                5000,
            )
        } catch (_: Exception) {
            // 自检失败就算了，别影响正常采集
        }
    }

    /** 深度优先收集文本，限深限量。 */
    private fun collect(node: AccessibilityNodeInfo?, depth: Int, out: MutableSet<String>, count: IntArray) {
        if (node == null || depth > 24 || count[0] >= MAX_NODES) return
        count[0]++

        val t = node.text?.toString()
        if (!t.isNullOrBlank()) out.add(t)
        val d = node.contentDescription?.toString()
        if (!d.isNullOrBlank()) out.add(d)

        for (i in 0 until node.childCount) {
            collect(node.getChild(i), depth + 1, out, count)
            if (count[0] >= MAX_NODES) break
        }
    }

    private data class Ev(val kind: String, val nickname: String, val question: String)

    /**
     * 一行公屏文字 → 我们的事件。
     *
     * 抖音公屏常见的几种写法（现场容易变，所以都写得宽松一点）：
     *   「小明 来了」「小明 进入直播间」      → welcome
     *   「小明 关注了主播」                  → follow
     *   「小明 点赞了主播」「小明 点亮了」    → thumb
     *   「小明：多少钱」「小明: 有货吗」      → bullet（问题）
     * 认不出来的短文本也按弹幕送过去 —— 服务端那层相关性判定会把闲聊挡掉（不花钱），
     * 比在这里猜错、把真问题漏掉要好。
     */
    private fun kindOf(text: String): String? {
        return when {
            text.contains("关注了") || text.contains("关注了主播") -> "follow"
            text.contains("点赞") || text.contains("点亮") -> "thumb"
            text.contains("来了") || text.contains("进入直播间") || text.contains("进来了") -> "welcome"
            // 纯数字/时间之类的界面文字，明显不是公屏内容
            Regex("^[\\d:：.\\-\\s%]+$").matches(text) -> null
            text.length < 2 -> null
            else -> "bullet"
        }
    }

    /** 从一行公屏文字里把昵称和正文拆开。 */
    private fun event(text: String, kind: String): Ev {
        // 「小明：多少钱」这种最好拆
        val m = Regex("^(.{1,20}?)[:：]\\s*(.+)$").find(text)
        if (m != null) {
            return Ev("bullet", m.groupValues[1].trim(), m.groupValues[2].trim())
        }
        // 「小明 来了 / 关注了主播 / 点赞了主播」：动作词之前是昵称
        for (word in listOf("来了", "进入直播间", "进来了", "关注了主播", "关注了", "点赞了主播", "点赞了", "点亮了")) {
            val i = text.indexOf(word)
            if (i > 0) {
                val nick = text.substring(0, i).trim()
                return Ev(kind, nick, "")
            }
        }
        // 认不出昵称：整行当内容，昵称留空（服务端会按统一称呼念）
        return Ev(kind, "", text)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
