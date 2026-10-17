package cc.luxiaofei.app

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * WebView 看护：加载失败、或页面根本没渲染出来时，别留一张空白页。
 *
 * 真机反馈的「App 打开一片空白」有两种原因，处理完全不同：
 *   ① 连不上（断网 / 域名 / 证书）→ 系统会回调 onReceivedError，这里换成中文提示；
 *   ② 连上了但页面没渲染（手机的系统 WebView 太旧，跑不动现代网页）→ 不报错、只是空白，
 *      只能主动探一次正文长度判断，然后提示「去更新 Android System WebView」，
 *      并把**系统 WebView 的版本**打在屏幕上 —— 用户截图一发就能定位。
 */
object WebDiag {

    /** 只提示一次，别把用户困在提示页 */
    private var helpShown = false

    fun client(ctx: Context): WebViewClient = object : WebViewClient() {

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return // 只管主页面
            val reason = error.description?.toString().orEmpty()
            view.loadDataWithBaseURL(
                null,
                page(ctx, "连不上服务器", "先看看手机网络（Wi-Fi / 流量）是否正常，再点下面重试。", "原因：$reason"),
                "text/html", "utf-8", null,
            )
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (helpShown) return
            view.evaluateJavascript(PROBE) { raw ->
                val s = raw ?: ""
                if (helpShown) return@evaluateJavascript
                if (!(s.contains("\"n\":-1") || s.contains("\"n\":0") || s.contains("\"err\":"))) return@evaluateJavascript
                helpShown = true
                view.loadDataWithBaseURL(
                    null,
                    page(
                        ctx,
                        "网页没能显示出来",
                        "这台手机的系统 WebView 可能太旧了：去应用商店更新「Android System WebView」" +
                            "（有的机型叫「安卓系统WebView」）或 Chrome，再重新打开本 App。",
                        "系统 WebView：${webViewVersion()}\n页面状态：${s.take(140)}",
                    ),
                    "text/html", "utf-8", null,
                )
            }
        }
    }

    /** 探页面渲染没有：正文字符数（-1 = 连 body 都没有） */
    private const val PROBE =
        "(function(){try{var b=document.body;return JSON.stringify({n:b?b.innerText.replace(/\\s+/g,'').length:-1});}" +
            "catch(e){return JSON.stringify({err:String(e&&e.message||e)});}})()"

    /** 系统 WebView 的包名 + 版本（要更新的就是它） */
    @SuppressLint("NewApi")
    fun webViewVersion(): String = try {
        val p = WebView.getCurrentWebViewPackage()
        if (p == null) "未知（系统没装 WebView？）" else "${p.packageName} ${p.versionName}"
    } catch (e: Throwable) {
        "未知（${e.javaClass.simpleName}）"
    }

    private fun page(ctx: Context, title: String, tip: String, detail: String): String {
        val url = try { ctx.getString(R.string.start_url) } catch (e: Throwable) { "" }
        return """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><style>
            body{margin:0;padding:56px 22px;background:#0b1220;color:#e8f0ff;text-align:center;
            font-family:-apple-system,"PingFang SC","Noto Sans SC",sans-serif}
            h1{font-size:17px;margin:0 0 12px}
            p{font-size:13.5px;line-height:1.8;color:#9fb3d9;margin:8px 0;text-align:left}
            .why{font-size:11px;color:#7d8fb3;margin-top:16px;word-break:break-all;text-align:left}
            a{display:inline-block;margin-top:24px;padding:14px 28px;border-radius:14px;color:#fff;
            text-decoration:none;font-size:15px;font-weight:600;background:linear-gradient(135deg,#4C1D95,#0EA5E9)}
            </style></head><body><h1>$title</h1><p>$tip</p><p class="why">$detail</p>
            <a href="$url">重新加载</a>
            <p class="why">（一直这样就把这一屏截图发给客服，上面那行版本号能直接定位问题）</p>
            </body></html>
        """.trimIndent()
    }
}
