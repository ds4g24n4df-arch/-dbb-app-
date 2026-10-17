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

        /**
         * 一次扫窗口最多看多少个节点。
         *
         * 600 太小了 —— 现场日志揭穿了后果：抖音屏幕上"顶部提示条/功能推广"排在前面，
         * 扫到 600 个节点就停了，**底部的公屏弹幕区根本没扫到**。于是"收 326 条"里
         * 全是「你当前的直播状态较好…」「开启AI经纪人…」这种界面文字，
         * 真观众的提问一条都没上来 —— 表现就是"问了不回"。
         * 放到 1500：能扫到底部的弹幕列表；再配合界面文字黑名单（见 UI_NOISE），
         * 既读得到真弹幕，又不会把垃圾灌进去。
         */
        private const val MAX_NODES = 1500
        /** 一次最多往里推几条：一批推多了会挤掉正在念的口播稿 */
        private const val MAX_EVENTS_PER_POST = 5
        /** 文本多短才算"公屏那一行的字"（太长的多半是公告/标题） */
        private const val MAX_TEXT_LEN = 60
        /** 两次扫窗口的最小间隔，公屏刷得再快也不用人眼跟不上的频率去扫 */
        private const val MIN_INTERVAL_MS = 400L

        /**
         * 这些是**抖音自己的界面文字，不是观众发的弹幕** —— 现场日志里抓到的原文：
         *   「你当前的直播状态较好，尝试邀请其他主播PK一下，有助于提升人气收入哦」
         *   「开启AI经纪人，人气翻倍」
         *   「商超零售-鹏哥」（昵称/榜单那类）
         * 它们被当成弹幕收进来之后，把"收多少条"刷虚、把互动日志冲得看不清真问题，
         * 还白占服务端的判定。命中这些词的行直接不推 —— 宁可少几条，也别让垃圾盖住真弹幕。
         *
         * 注意：**只过滤"抖音运营/推广"这类明显的界面词**，不按"像不像昵称"过滤 ——
         * 「多少钱」这种真弹幕长得也像昵称，一过滤就会把真问题漏掉。
         */
        private val UI_NOISE = listOf(
            "直播状态", "尝试邀请", "邀请其他", "PK一下", "人气", "经纪人", "助力",
            "去开通", "立即开通", "开通", "升级", "权益", "任务", "奖励", "规则说明",
            "榜单", "小时榜", "人气榜", "贡献榜", "点击", "立即", "推广", "官方",
            // 观看人数这类也是界面文字：现场日志里抓到过「2051人看过」「2039人看过」，
            // 它们被当弹幕收进来，把互动日志刷得全是"（无内容）"，真弹幕反而被埋掉。
            "人看过", "人观看", "人正在看", "在线人数",
        )

        /**
         * 抖音的按钮/入口名字，往往就是两三个字，和真弹幕长得一模一样 ——
         * 所以这份**只在"整行完全相同"时才过滤**（不会误伤「有礼物吗」「怎么进购物车」这种真问题）。
         */
        private val UI_NOISE_EXACT = setOf(
            "礼物", "表情入口", "小心心", "更多", "分享", "点赞", "关注", "购物车", "评论",
            "观众头像", "按钮", "关闭", "举报", "设置", "榜单", "热榜",
        )
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

        // 扫窗口、发请求都不在系统回调线程上做（不然手机会卡）。
        // 注意：这里**不**直接用 rootInActiveWindow —— 抖音每次切页、弹礼物动画、有悬浮层时，
        // 它经常是 null（现场日志里就抓到过"抖音事件到了，但拿不到窗口内容（root=null）"）。
        // 取窗口（含遍历所有窗口 + 重试）放在 scanAndPush 里做。
        pool.execute { scanAndPush(pkg) }
    }

    /**
     * 扫一遍窗口，挑出"新出现的公屏文字"，成批发给入站接口。
     *
     * 为什么按包名重新解析、还要重试：抖音切页 / 礼物动画 / 悬浮层期间 rootInActiveWindow 常为 null，
     * 或者拿到的是别的窗口（输入法、系统浮层）。所以：
     *   1. 先看当前活动窗口（包名对上就用）；
     *   2. 不对就**遍历所有窗口**找抖音那个；
     *   3. 都没有就隔 150ms 再试两次（抖音正在切页时，稍等一下就有了）。
     */
    private fun scanAndPush(pkg: String) {
        var texts = LinkedHashSet<String>()
        var root: AccessibilityNodeInfo? = null
        for (attempt in 0..2) {
            root = rootNodeFor(pkg)
            texts = LinkedHashSet()
            if (root != null) {
                try {
                    collect(root, 0, texts, intArrayOf(0))
                } catch (_: Exception) {
                    texts = LinkedHashSet()
                }
            }
            if (texts.isNotEmpty()) break
            if (attempt < 2) sleepQuiet(150L)
        }

        if (root == null) {
            diag("抖音事件到了，但拿不到窗口内容（root=null）")
            return
        }

        val now = System.currentTimeMillis()
        // 30 分钟（原来 5 分钟）：公屏是滚动列表，同一行能在屏幕上挂很久。
        // 5 分钟的窗太短 —— 挂着的旧弹幕会被当成"新出现的"重新推一遍，
        // 于是"收 N 条"里全是重复，而且每条都会去挤占 15 秒的回复节流，
        // 真观众的提问反而被顶掉（现场就是"问了不回"）。
        seen.entries.removeAll { now - it.value > 30 * 60 * 1000 }

            val freshTexts = ArrayList<String>()    
            val fresh = texts.mapNotNull { raw ->       
            val text = raw.trim().replace('\n', ' ').replace(Regex("\\s+"), " ")
            if (text.isEmpty() || text.length > MAX_TEXT_LEN) return@mapNotNull null
            if (UI_NOISE.any { text.contains(it) }) return@mapNotNull null
            if (text in UI_NOISE_EXACT) return@mapNotNull null
            val kind = kindOf(text) ?: return@mapNotNull null
            if (seen.containsKey(text)) return@mapNotNull null
            seen[text] = now
            freshTexts.add(text)
            event(text, kind)
        }.take(MAX_EVENTS_PER_POST)

        // 自检：把"看到几行字、认出几行 + 样例"推到服务端（每 30 秒最多一条）。
        // 这条自检写进互动日志、但不出声、不占回复节流、也不计入"收多少条"。
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
                val sent = Http.postJson(
            "$base/api/live/inbound",
            json,
            Prefs.deviceKey(this),
            Prefs.authToken(this),
            // 短超时：读屏是"顺带做的事"，网络慢不该压着后面的扫描
            5000,
        )
        if (sent == null) {
            // 送失败：把这些行从"已送"里撤掉，下一轮重试。
            // 不撤的话它们在 30 分钟内再也不会推 —— 现场表现就是"问了不回"。
            freshTexts.forEach { seen.remove(it) }
        }
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

    /**
     * 找"抖音那个窗口"的内容根。
     *
     * 先看当前活动窗口（包名对上就用）；不对就遍历所有窗口找抖音的那个；
     * 实在都没有就返回 null（调用方会重试 / 上报自检）。
     */
    private fun rootNodeFor(pkg: String): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() == pkg) return active
        try {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg) return r
            }
        } catch (_: Exception) {
            // windows 在个别 ROM 上会抛异常，忽略，退回 active
        }
        return active
    }

    /** 小睡（只在工作线程里用）。 */
    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 深度优先收集文本，限深限量。 */
    private fun collect(node: AccessibilityNodeInfo?, depth: Int, out: MutableSet<String>, count: IntArray) {
        if (node == null || depth > 24 || count[0] >= MAX_NODES) return
        count[0]++

        val t = node.text?.toString()
        if (!t.isNullOrBlank()) out.add(t)
        /**
         * **故意不收 contentDescription** —— 这是这次定位到的真凶：
         * 抖音把"礼物""表情入口""小心心""观众头像3，按钮"这些**控件说明**放在 contentDescription 里，
         * 而观众发的话在 text 里。以前两样都收 → 日志里全是控件名词（现场截图就是
         * 「观众：观众头像3，按钮」「表情入口」「小心心」），真弹幕反而被埋掉。
         * 只收 text，控件那批自然就没了。
         */

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
                        // 关注/点赞要**先判**：抖音显示的是「鹏哥 来了·新增关注」，
            // 先命中"来了"就会被当成进人丢掉（进人欢迎是固定关闭的）→ 感谢语永远不出声。
            text.contains("关注") || text.contains("新增关注") -> "follow"
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
