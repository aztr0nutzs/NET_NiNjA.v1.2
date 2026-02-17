package com.netninja

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class OpenClawWsValidationTest {
  private val parser = Json { ignoreUnknownKeys = true }

  private inline fun <reified T> assertIsInstance(value: Any?): T {
    assertTrue("Expected ${T::class.simpleName} but got ${value?.let { it::class.simpleName } ?: "null"}", value is T)
    return value as T
  }

  @Test
  fun rejectsMalformedJson() {
    val result = validateOpenClawWsMessage("{", currentNodeId = null, parser = parser)
    val invalid = assertIsInstance<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("MALFORMED_JSON", invalid.error.code)
  }

  @Test
  fun rejectsUnknownType() {
    val result = validateOpenClawWsMessage("""{"type":"BOGUS"}""", currentNodeId = null, parser = parser)
    val invalid = assertIsInstance<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("UNKNOWN_TYPE", invalid.error.code)
  }

  @Test
  fun rejectsMissingHelloNodeId() {
    val result = validateOpenClawWsMessage("""{"type":"HELLO"}""", currentNodeId = null, parser = parser)
    val invalid = assertIsInstance<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("MISSING_NODE_ID", invalid.error.code)
  }

  @Test
  fun rejectsUnsupportedProtocolVersion() {
    val result = validateOpenClawWsMessage(
      """{"type":"HELLO","nodeId":"n1","protocolVersion":2}""",
      currentNodeId = null,
      parser = parser
    )
    val invalid = assertIsInstance<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("UNSUPPORTED_PROTOCOL_VERSION", invalid.error.code)
  }

  @Test
  fun rejectsResultWithoutPayload() {
    val result = validateOpenClawWsMessage("""{"type":"RESULT","nodeId":"n1"}""", currentNodeId = null, parser = parser)
    val invalid = assertIsInstance<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("MISSING_PAYLOAD", invalid.error.code)
  }

  @Test
  fun acceptsHelloWithDefaultVersion() {
    val result = validateOpenClawWsMessage(
      """{"type":"HELLO","nodeId":" node-1 "}""",
      currentNodeId = null,
      parser = parser
    )
    val valid = assertIsInstance<OpenClawWsValidationResult.Valid>(result)
    assertEquals("HELLO", valid.message.type)
    assertEquals("node-1", valid.message.nodeId)
    assertEquals(1, valid.message.protocolVersion)
  }

  @Test
  fun acceptsResultUsingBoundNodeContext() {
    val result = validateOpenClawWsMessage(
      """{"type":"RESULT","payload":"ok"}""",
      currentNodeId = "node-1",
      parser = parser
    )
    val valid = assertIsInstance<OpenClawWsValidationResult.Valid>(result)
    assertEquals("RESULT", valid.message.type)
    assertEquals("ok", valid.message.payload)
  }
}
