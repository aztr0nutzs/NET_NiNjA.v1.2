package core.discovery

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OuiDbTest {
  @Test
  fun lookupResolvesKnownRaspberryPiPrefix() {
    val vendor = OuiDb.lookup("B8:27:EB:12:34:56")
    assertNotNull(vendor)
    assertTrue(vendor.contains("raspberry", ignoreCase = true))
  }
}
