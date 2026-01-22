
package com.netninja

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
  private val logTag = "NetNinjaWebView"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Start engine service (foreground) so localhost server is up.
    startForegroundService(Intent(this, EngineService::class.java))

    val web = WebView(this)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    web.settings.mediaPlaybackRequiresUserGesture = false
    web.clearCache(true)
    loadSplash(web)
    web.webChromeClient = object : WebChromeClient() {
      override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        Log.d(
          logTag,
          "console: ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
        )
        return true
      }
    }
    web.webViewClient = object : WebViewClient() {
      override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
      ) {
        if (request.isForMainFrame) {
          Log.e(logTag, "load error: ${error.errorCode} ${error.description}")
        }
      }

      override fun onPageFinished(view: WebView, url: String) {
        view.evaluateJavascript(
          "window.onerror=function(m,s,l,c,e){console.log('JS_ERROR:'+m+'@'+s+':'+l+':'+c);};",
          null
        )
      }
    }

    waitForServerAndLoad(web, "http://127.0.0.1:8787/ui/ninja_mobile.html")

    setContentView(web)
  }

  private fun waitForServerAndLoad(web: WebView, url: String) {
    val main = Handler(Looper.getMainLooper())
    thread(name = "NetNinjaServerProbe") {
      val deadline = System.currentTimeMillis() + 150_000L
      var delayMs = 150L
      while (System.currentTimeMillis() < deadline) {
        if (isServerReady()) {
          main.post { web.loadUrl(url) }
          return@thread
        }
        Thread.sleep(delayMs)
        delayMs = (delayMs * 1.2).toLong().coerceAtMost(1250L)
      }
      main.post { web.loadUrl(url) }
    }
  }

  private fun loadSplash(web: WebView) {
    val baseUrl = "file:///android_asset/web-ui/new_assets/"
    val html = """
      <!doctype html>
      <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>Net Ninja</title>
        <style>
          :root { color-scheme: dark; }
          html, body { height: 100%; margin: 0; }
          body {
            font-family: "SF Mono", "Fira Code", "Consolas", monospace;
            background: radial-gradient(120% 120% at 10% 10%, #1b2b3b, #05080c);
            color: #d5e9ff;
            display: grid;
            place-items: center;
          }
          .panel {
            text-align: center;
            padding: 28px 30px;
            border: 1px solid rgba(120, 200, 255, 0.25);
            border-radius: 14px;
            background: rgba(5, 12, 18, 0.65);
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.45);
          }
          .title { font-size: 18px; letter-spacing: 2px; text-transform: uppercase; }
          .sub { font-size: 13px; opacity: 0.8; margin-top: 8px; }
          .spinner {
            width: 36px; height: 36px; margin: 18px auto 0;
            border: 3px solid rgba(213, 233, 255, 0.15);
            border-top-color: #7cd4ff; border-radius: 50%;
            animation: spin 1s linear infinite;
          }
          video {
            width: 240px;
            max-width: 70vw;
            height: auto;
            border-radius: 12px;
            box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);
            display: block;
            margin: 14px auto 6px;
          }
          @keyframes spin { to { transform: rotate(360deg); } }
        </style>
      </head>
      <body>
        <div class="panel">
          <div class="title">Net Ninja</div>
          <div class="sub">Starting local engine...</div>
          <video autoplay muted loop playsinline>
            <source src="ninja_boot.mp4" type="video/mp4" />
          </video>
          <div class="spinner"></div>
        </div>
      </body>
      </html>
    """.trimIndent()
    web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
  }

  private fun isServerReady(): Boolean {
    return runCatching {
      val conn = (URL("http://127.0.0.1:8787/api/v1/system/info").openConnection() as HttpURLConnection).apply {
        connectTimeout = 500
        readTimeout = 500
        requestMethod = "GET"
      }
      conn.inputStream.use { }
      conn.responseCode in 200..399
    }.getOrDefault(false)
  }
}
