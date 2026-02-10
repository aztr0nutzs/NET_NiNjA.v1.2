package com.netninja

import android.app.Application
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityIntegrationTest {

  @Test
  fun bootStartsEngineServiceAndLoadsWebViewWithToken() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val expectedToken = LocalApiAuth.getOrCreateToken(app)

    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    val activity = controller.get()

    val shadowApp = shadowOf(app)
    val started = shadowApp.nextStartedService
    assertNotNull(started)
    assertEquals(EngineService::class.java.name, started.component?.className)

    val webField = MainActivity::class.java.getDeclaredField("web").apply { isAccessible = true }
    val web = webField.get(activity) as WebView
    val lastUrl = shadowOf(web).lastLoadedUrl
    assertNotNull(lastUrl)
    assertTrue(lastUrl.contains("token=$expectedToken"))
  }
}

