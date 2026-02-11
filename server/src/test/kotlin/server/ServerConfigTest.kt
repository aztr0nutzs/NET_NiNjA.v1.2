package server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerConfigTest {
  @Test
  fun usesDefaultsWhenEnvNotProvided() {
    val cfg = resolveServerConfig(emptyMap())
    assertEquals("127.0.0.1", cfg.host)
    assertEquals(8787, cfg.port)
    assertEquals("netninja.db", cfg.dbPath)
    assertNull(cfg.authToken)
    assertTrue(cfg.allowedOrigins.contains("http://127.0.0.1:8787"))
    assertTrue(cfg.allowedOrigins.contains("http://localhost:8787"))
  }

  @Test
  fun supportsEnvOverridesAndPortParsing() {
    val cfg = resolveServerConfig(
      mapOf(
        "NET_NINJA_HOST" to "0.0.0.0",
        "NET_NINJA_PORT" to "9090",
        "NET_NINJA_DB" to "/tmp/netninja.db",
        "NET_NINJA_ALLOWED_ORIGINS" to "http://a.example, http://b.example ",
        "NET_NINJA_TOKEN" to " secret-token "
      )
    )

    assertEquals("0.0.0.0", cfg.host)
    assertEquals(9090, cfg.port)
    assertEquals("/tmp/netninja.db", cfg.dbPath)
    assertEquals(listOf("http://a.example", "http://b.example"), cfg.allowedOrigins)
    assertEquals("secret-token", cfg.authToken)
  }

  @Test
  fun fallsBackToDefaultPortWhenPortIsInvalid() {
    val cfg = resolveServerConfig(mapOf("NET_NINJA_PORT" to "70000"))
    assertEquals(8787, cfg.port)
  }
}
