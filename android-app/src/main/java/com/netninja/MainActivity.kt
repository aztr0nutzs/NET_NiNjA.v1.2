
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
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
  private val logTag = "NetNinjaWebView"

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Start engine service (foreground) so localhost server is up.
    startForegroundService(Intent(this, EngineService::class.java))

    val web = WebView(this)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
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

    // Give the server a moment to start; then load same-origin UI to avoid CORS/file restrictions.
    Handler(Looper.getMainLooper()).postDelayed({
      web.loadUrl("http://127.0.0.1:8787/ui/ninja_mobile.html")
    }, 350)

    setContentView(web)
  }
}
