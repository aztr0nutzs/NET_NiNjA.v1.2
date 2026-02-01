
package com.netninja

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.netninja.openclaw.OpenClawGatewayService
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
  private val logTag = "NetNinjaWebView"
  private val permissionRequestCode = 1201
  private var locationPrompted = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Start engine service (foreground) so localhost server is up.
    startForegroundService(Intent(this, EngineService::class.java))
    startOpenClawGateway()
    ensureRuntimePermissions()
    ensureLocationServicesEnabled()

    val web = WebView(this)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    web.settings.mediaPlaybackRequiresUserGesture = false
    web.settings.allowFileAccessFromFileURLs = true
    web.settings.allowUniversalAccessFromFileURLs = true
    web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    web.clearCache(true)
    web.setBackgroundColor(Color.BLACK)
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
      override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        Log.d(logTag, "page start: $url")
      }

      override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
      ) {
        if (request.isForMainFrame) {
          Log.e(logTag, "load error: ${error.errorCode} ${error.description}")
        }
      }

      override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: android.webkit.WebResourceResponse
      ) {
        if (request.isForMainFrame) {
          Log.e(logTag, "http error: ${errorResponse.statusCode} ${errorResponse.reasonPhrase} ${request.url}")
        }
      }

      override fun onPageFinished(view: WebView, url: String) {
        view.evaluateJavascript(
          "window.onerror=function(m,s,l,c,e){console.log('JS_ERROR:'+m+'@'+s+':'+l+':'+c);};",
          null
        )
      }
    }

    val serverDashboardUrl = "http://127.0.0.1:8787/ui/ninja_mobile_new.html"
    val assetDashboardUrl = "file:///android_asset/web-ui/ninja_mobile_new.html?bootstrap=1"
    web.loadUrl(assetDashboardUrl)
    waitForServerAndLoad(web, serverDashboardUrl)

    setContentView(web)
  }

  override fun onResume() {
    super.onResume()
    ensureLocationServicesEnabled()
  }

  private fun ensureRuntimePermissions() {
    val needed = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
      != PackageManager.PERMISSION_GRANTED
    ) {
      needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
      ) {
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
      != PackageManager.PERMISSION_GRANTED
    ) {
      needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (needed.isNotEmpty()) {
      ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequestCode)
    }
  }

  private fun ensureLocationServicesEnabled() {
    if (locationPrompted) return
    val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      locationManager.isLocationEnabled
    } else {
      locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    if (!enabled) {
      locationPrompted = true
      AlertDialog.Builder(this)
        .setTitle("Enable location services")
        .setMessage("Wi‑Fi scanning requires location services to be enabled. Turn on Location to discover devices.")
        .setPositiveButton("Open Settings") { _, _ ->
          startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        .setNegativeButton("Cancel", null)
        .show()
    }
  }

  private fun startOpenClawGateway() {
    val intent = Intent(this, OpenClawGatewayService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(intent)
    } else {
      startService(intent)
    }
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
