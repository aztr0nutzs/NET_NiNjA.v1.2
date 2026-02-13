@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.netninja.routercontrol

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.netninja.EngineService
import com.netninja.json.booleanOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class RouterControlActivity : AppCompatActivity() {
  private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
  }

  private lateinit var webView: WebView
  private lateinit var client: RouterControlGatewayClient

  @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    startForegroundService(android.content.Intent(this, EngineService::class.java))
    client = RouterControlGatewayClient(applicationContext)

    webView = WebView(this).apply {
      setBackgroundColor(Color.BLACK)
      webChromeClient = WebChromeClient()
      webViewClient = WebViewClient()
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.cacheMode = WebSettings.LOAD_NO_CACHE
      settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
      settings.allowFileAccess = false
      settings.allowContentAccess = false
      settings.allowFileAccessFromFileURLs = false
      settings.allowUniversalAccessFromFileURLs = false
      addJavascriptInterface(RouterJsBridge(), "NetNinjaRouterBridge")
      loadUrl("http://127.0.0.1:8787/ui/new_assets/router_cntrl_dash.html?bridge=native")
    }
    setContentView(webView)
  }

  override fun onDestroy() {
    super.onDestroy()
    uiScope.cancel()
    webView.removeJavascriptInterface("NetNinjaRouterBridge")
    webView.destroy()
  }

  private fun pushState(state: JsonObject) {
    val payload = json.encodeToString(JsonObject.serializer(), state)
      .replace("\\", "\\\\")
      .replace("'", "\\'")
      .replace("\n", "\\n")
    val script = "window.__NET_NINJA__&&window.__NET_NINJA__.setState&&window.__NET_NINJA__.setState(JSON.parse('$payload'));"
    uiScope.launch {
      webView.evaluateJavascript(script, null)
    }
  }

  inner class RouterJsBridge {
    @JavascriptInterface
    fun request(raw: String): String {
      return runCatching {
        val req = json.parseToJsonElement(raw).jsonObject
        val action = req["action"]?.jsonPrimitive?.content?.trim().orEmpty()
        val payload = req["payload"]
        val remember = req["remember"]?.jsonPrimitive?.booleanOrNull == true

        val result = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
          when (action) {
            "http" -> {
              val p = payload?.jsonObject ?: JsonObject(emptyMap())
              val method = p["method"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "GET" }
              val path = p["path"]?.jsonPrimitive?.content?.trim().orEmpty()
              val body = p["body"]
              val data = client.handleHttp(method = method, path = path, body = body, remember = remember)
              runCatching { client.refreshSnapshot() }
              buildJsonObject {
                put("ok", true)
                put("data", data ?: JsonNull)
              }
            }

            "refresh" -> {
              client.refreshSnapshot()
              buildJsonObject { put("ok", true) }
            }

            "logout" -> {
              client.logout()
              buildJsonObject { put("ok", true) }
            }

            else -> buildJsonObject {
              put("ok", false)
              put("error", "Unsupported bridge action: $action")
            }
          }
        }

        val state = kotlinx.coroutines.runBlocking(Dispatchers.IO) { client.buildState() }
        pushState(state)

        val merged = result.toMutableMap().apply {
          put("state", state)
        }
        json.encodeToString(JsonObject.serializer(), JsonObject(merged))
      }.getOrElse { err ->
        val state = kotlinx.coroutines.runBlocking(Dispatchers.IO) { client.buildState(error = err.message ?: "Bridge error") }
        pushState(state)
        val out = buildJsonObject {
          put("ok", false)
          put("error", err.message ?: "Bridge error")
          put("state", state)
        }
        json.encodeToString(JsonObject.serializer(), out)
      }
    }
  }
}

private val JsonElement?.jsonPrimitive: JsonPrimitive?
  get() = this as? JsonPrimitive
