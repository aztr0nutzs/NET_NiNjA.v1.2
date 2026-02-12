package com.netninja

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.netninja.routercontrol.RouterControlActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouterControlSmokeInstrumentedTest {
  @Test
  fun routerControlActivityLaunches() {
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("com.netninja", appContext.packageName)
    ActivityScenario.launch(RouterControlActivity::class.java).use { }
  }
}
