
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
import android.widget.FrameLayout
import android.view.Gravity
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.netninja.ninja.NinjaAnimatedImageView
import com.netninja.ninja.NinjaCompanionController
import com.netninja.ninja.NinjaThoughtBubbleView
import com.netninja.routercontrol.RouterControlActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
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
  private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var scanProgressJob: Job? = null

  /** Controller managing the ninja companion lifecycle and behaviours. */
  private var ninjaController: NinjaCompanionController? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    bootStartedAtMs = System.currentTimeMillis()

    // Start engine service (foreground) so localhost server is up.
    startForegroundService(Intent(this, EngineService::class.java))
    ensureRuntimePermissions()
    ensureLocationServicesEnabled()

    val localApiToken = LocalApiAuth.getOrCreateToken(applicationContext)
    val serverDashboardUrl = Uri.parse("http://127.0.0.1:8787/ui/ninja_hud.html")
      .buildUpon()
      .appendQueryParameter(LocalApiAuth.QUERY_PARAM, localApiToken)
      .build()
      .toString()
    val assetDashboardUrl = Uri.parse("file:///android_asset/web-ui/ninja_hud.html")
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
        val needsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        val micOk = !needsMic || ContextCompat.checkSelfPermission(
          this@MainActivity,
          Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (micOk) {
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
        pushScanProgressToWeb(EngineService.scanProgressFlow.value)
      }
    }

    // Prefer serving everything from the local server. If it's not ready yet, WebViewClient will fall back to assets.
    web.loadUrl(serverDashboardUrl)
    waitForServerAndLoad(web, serverDashboardUrl, assetDashboardUrl)

    setContentView(web)
    // Attach the ninja overlay after setting the content view.
    attachNinjaCompanion()
    observeScanProgress()
  }

  override fun onStart() {
    super.onStart()
    // Start the ninja animations and event subscriptions
    ninjaController?.start()
  }

  override fun onStop() {
    // Stop the ninja to avoid leaks when the activity is no longer visible
    ninjaController?.stop()
    super.onStop()
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

  override fun onDestroy() {
    scanProgressJob?.cancel()
    uiScope.cancel()
    super.onDestroy()
  }

  /**
   * Programmatically attach the ninja companion overlay on top of whatever
   * content this activity is showing.  The bubble and thought bubble are
   * positioned relative to the bottom right of the screen.  Calling this
   * repeatedly will remove the previous overlay.
   */
  private fun attachNinjaCompanion() {
    val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
    // Use the first child if present to overlay just on top of our content view
    val overlay: ViewGroup = (root.getChildAt(0) as? ViewGroup) ?: root
    // Remove any existing ninja views before adding new ones
    val existingBubble = overlay.findViewById<NinjaAnimatedImageView>(R.id.ninja_companion_bubble)
    val existingThought = overlay.findViewById<NinjaThoughtBubbleView>(R.id.ninja_companion_thought)
    if (existingBubble != null) overlay.removeView(existingBubble)
    if (existingThought != null) overlay.removeView(existingThought)
    // Create bubble view
    val ninja = NinjaAnimatedImageView(this).apply {
      layoutParams = FrameLayout.LayoutParams(dp(64), dp(64)).apply {
        gravity = Gravity.END or Gravity.BOTTOM
        marginEnd = dp(14)
        bottomMargin = dp(90)
      }
      setBackgroundResource(R.drawable.ninja_bubble_bg)
      setPadding(dp(6), dp(6), dp(6), dp(6))
      elevation = dpF(10f)
    }
    // Create thought bubble view
    val thought = NinjaThoughtBubbleView(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.END or Gravity.BOTTOM
        marginEnd = dp(14) + dp(70)
        bottomMargin = dp(90) + dp(40)
      }
      elevation = dpF(11f)
    }
    // Example click behaviour: show a simple message when tapped
    ninja.setOnClickListener {
      thought.show("I'm watching your packets. Respectfully.")
    }
    overlay.addView(thought)
    overlay.addView(ninja)
    ninjaController = NinjaCompanionController(ninja, thought)
  }

  private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
  private fun dpF(v: Float): Float = v * resources.displayMetrics.density

  private fun observeScanProgress() {
    if (scanProgressJob != null) return
    scanProgressJob = uiScope.launch {
      EngineService.scanProgressFlow.collectLatest { progress ->
        pushScanProgressToWeb(progress)
      }
    }
  }

  private fun pushScanProgressToWeb(progress: ScanProgress) {
    val payload = JSONObject().apply {
      put("progress", progress.progress)
      put("phase", progress.phase)
      put("message", progress.message)
      put("fixAction", progress.fixAction)
      put("networks", progress.networks)
      put("devices", progress.devices)
      put("rssiDbm", progress.rssiDbm)
      put("ssid", progress.ssid)
      put("bssid", progress.bssid)
      put("subnet", progress.subnet)
      put("gateway", progress.gateway)
      put("linkUp", progress.linkUp)
      put("updatedAt", progress.updatedAt)
    }.toString()
    web.evaluateJavascript(
      """
      (function(){
        const data = $payload;
        if (typeof window.onNativeScanProgress === "function") window.onNativeScanProgress(data);
        if (typeof window.applyScanData === "function") window.applyScanData(data);
      })();
      """.trimIndent(),
      null
    )
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
