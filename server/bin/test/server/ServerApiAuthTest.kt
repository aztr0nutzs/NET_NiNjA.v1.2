package server

import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerApiAuthTest {
  @Test
  fun tokenGenerationAndValidation() {
    val dir = Files.createTempDirectory("netninja-auth-1")
    val dbPath = dir.resolve("netninja.db").absolutePathString()

    val token = ServerApiAuth.loadOrCreate(dbPath = dbPath, envToken = null)
    assertTrue(token.length >= 24)
    assertTrue(ServerApiAuth.validate(token))
    assertFalse(ServerApiAuth.validate("wrong-token"))
  }

  @Test
  fun envTokenOverridesGeneratedToken() {
    val dir = Files.createTempDirectory("netninja-auth-2")
    val dbPath = dir.resolve("netninja.db").absolutePathString()

    val token = ServerApiAuth.loadOrCreate(dbPath = dbPath, envToken = "fixed-token")
    assertEquals("fixed-token", token)
    assertTrue(ServerApiAuth.validate("fixed-token"))
  }

  @Test
  fun rotationMaintainsGracePeriodForPreviousToken() {
    val dir = Files.createTempDirectory("netninja-auth-3")
    val dbPath = dir.resolve("netninja.db").absolutePathString()

    val old = ServerApiAuth.loadOrCreate(dbPath = dbPath, envToken = "old-token")
    val rotated = ServerApiAuth.rotate(dbPath = dbPath, graceMs = 1_000L)

    assertNotEquals(old, rotated.token)
    assertTrue(ServerApiAuth.validate(rotated.token, nowMs = rotated.previousValidUntilMs + 5_000L))
    assertTrue(ServerApiAuth.validate(old, nowMs = rotated.previousValidUntilMs))
    assertFalse(ServerApiAuth.validate(old, nowMs = rotated.previousValidUntilMs + 1L))
  }
}
