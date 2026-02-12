
package com.netninja

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.netninja.routercontrol.RouterControlActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
  private val logTag = "NetNinjaWebView"
  private val permissionRequestCode = 1201
  private var locationPrompted = false
  private val permissionPrefsName = "netninja_permissions"
  private lateinit var web: WebView
  private var serverFallbackTriggered = false
  private var bootStartedAtMs: Long = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    bootStartedAtMs = System.currentTimeMillis()

    // Start engine service (foreground) so localhost server is up.
    startForegroundService(Intent(this, EngineService::class.java))
    ensureRuntimePermissions()
    ensureLocationServicesEnabled()

    val localApiToken = LocalApiAuth.getOrCreateToken(applicationContext)
    val serverDashboardUrl = Uri.parse("http://127.0.0.1:8787/ui/ninja_mobile_new.html")
      .buildUpon()
      .appendQueryParameter(LocalApiAuth.QUERY_PARAM, localApiToken)
      .build()
      .toString()
    val assetDashboardUrl = Uri.parse("file:///android_asset/web-ui/ninja_mobile_new.html")
      .buildUpon()
      .appendQueryParameter("bootstrap", "1")
      .appendQueryParameter(LocalApiAuth.QUERY_PARAM, localApiToken)
      .build()
      .toString()

    web = WebView(this)
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    web.settings.mediaPlaybackRequiresUserGesture = false
    web.settings.useWideViewPort = true
    web.settings.loadWithOverviewMode = true
    web.settings.setSupportZoom(false)
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

      override fun onPermissionRequest(request: PermissionRequest) {
        // Keep behavior safe-by-default: only grant if the corresponding Android permission is granted.
        val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val needsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        val cameraOk = !needsCamera || ContextCompat.checkSelfPermission(
          this@MainActivity,
          Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val micOk = !needsMic || ContextCompat.checkSelfPermission(
          this@MainActivity,
          Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraOk && micOk) {
          request.grant(request.resources)
        } else {
          request.deny()
        }
      }
    }
    web.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        if (uri.scheme.equals("netninja", ignoreCase = true) && uri.host.equals("router-control", ignoreCase = true)) {
          startActivity(Intent(this@MainActivity, RouterControlActivity::class.java))
          return true
        }
        return false
      }

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

          val isLocalServerUrl = request.url.toString().startsWith("http://127.0.0.1:8787/")
          val withinBootstrapWindow = System.currentTimeMillis() - bootStartedAtMs < 20_000L
          if (isLocalServerUrl && withinBootstrapWindow && !serverFallbackTriggered) {
            serverFallbackTriggered = true
            Log.w(logTag, "Local server not ready; falling back to asset bootstrap page.")
            view.loadUrl(assetDashboardUrl)
          }
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

    // Prefer serving everything from the local server. If it's not ready yet, WebViewClient will fall back to assets.
    web.loadUrl(serverDashboardUrl)
    waitForServerAndLoad(web, serverDashboardUrl, assetDashboardUrl)

    setContentView(web)
  }

  override fun onResume() {
    super.onResume()
    PermissionBridge.setForegroundActivity(this)
    ensureLocationServicesEnabled()
    ensureRuntimePermissions()
  }

  override fun onPause() {
    super.onPause()
    PermissionBridge.setForegroundActivity(null)
  }

  private fun ensureRuntimePermissions() {
    val needed = mutableListOf<String>()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
      != PackageManager.PERMISSION_GRANTED
    ) {
      if (!isPermanentlyDenied(Manifest.permission.ACCESS_FINE_LOCATION)) {
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        != PackageManager.PERMISSION_GRANTED
      ) {
        if (!isPermanentlyDenied(Manifest.permission.ACCESS_COARSE_LOCATION)) {
          needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
      }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
      != PackageManager.PERMISSION_GRANTED
    ) {
      if (!isPermanentlyDenied(Manifest.permission.NEARBY_WIFI_DEVICES)) {
        needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
      }
    }
    if (needed.isNotEmpty()) {
      ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequestCode)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (
      requestCode != permissionRequestCode &&
      requestCode != PermissionBridge.REQ_CAMERA &&
      requestCode != PermissionBridge.REQ_MIC &&
      requestCode != PermissionBridge.REQ_NOTIFICATIONS
    ) {
      return
    }
    permissions.forEachIndexed { index, permission ->
      val granted = grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
      val permanentlyDenied = !granted && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
      updatePermanentDenial(permission, permanentlyDenied)
    }
  }

  private fun updatePermanentDenial(permission: String, permanentlyDenied: Boolean) {
    val prefs = getSharedPreferences(permissionPrefsName, MODE_PRIVATE)
    prefs.edit()
      .putBoolean("${permission}_permanent_denied", permanentlyDenied)
      .apply()
  }

  private fun isPermanentlyDenied(permission: String): Boolean {
    val prefs = getSharedPreferences(permissionPrefsName, MODE_PRIVATE)
    return prefs.getBoolean("${permission}_permanent_denied", false)
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
        .setMessage("Wi-Fi scanning requires location services to be enabled. Turn on Location to discover devices.")
        .setPositiveButton("Open Settings") { _, _ ->
          startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        .setNegativeButton("Cancel", null)
        .show()
    }
  }

  private fun waitForServerAndLoad(web: WebView, url: String, assetFallbackUrl: String) {
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
      // Still attempt to load the server URL; if it fails the WebViewClient will use the asset fallback.
      main.post {
        if (!serverFallbackTriggered) {
          web.loadUrl(assetFallbackUrl)
        }
        web.loadUrl(url)
      }
    }
  }

  private fun isServerReady(): Boolean {
    return runCatching {
      val token = LocalApiAuth.getOrCreateToken(applicationContext)
      val conn = (URL("http://127.0.0.1:8787/api/v1/system/info").openConnection() as HttpURLConnection).apply {
        connectTimeout = 500
        readTimeout = 500
        requestMethod = "GET"
        setRequestProperty(LocalApiAuth.HEADER_TOKEN, token)
      }
      conn.inputStream.use { }
      conn.responseCode in 200..399
    }.getOrDefault(false)
  }
}
