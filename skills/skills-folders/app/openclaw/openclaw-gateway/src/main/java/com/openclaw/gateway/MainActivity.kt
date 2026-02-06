
package com.openclaw.gateway

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity: AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.webViewClient = WebViewClient()
        web.loadUrl("http://YOUR_GATEWAY_IP:18789")
        setContentView(web)
    }
}
