package server.openclaw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenClawWebSocketValidationTest {
  @Test
  fun rejectsMalformedJson() {
    val result = OpenClawGatewayState.parseAndValidateMessage("{", currentNodeId = null)
    val invalid = assertIs<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("MALFORMED_JSON", invalid.error.code)
  }

  @Test
  fun rejectsUnknownType() {
    val result = OpenClawGatewayState.parseAndValidateMessage("""{"type":"BOGUS"}""", currentNodeId = null)
    val invalid = assertIs<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("UNKNOWN_TYPE", invalid.error.code)
  }

  @Test
  fun rejectsMissingHelloNodeId() {
    val result = OpenClawGatewayState.parseAndValidateMessage("""{"type":"HELLO"}""", currentNodeId = null)
    val invalid = assertIs<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("MISSING_NODE_ID", invalid.error.code)
  }

  @Test
  fun rejectsUnsupportedProtocolVersion() {
    val result = OpenClawGatewayState.parseAndValidateMessage(
      """{"type":"HELLO","nodeId":"n1","protocolVersion":99}""",
      currentNodeId = null
    )
    val invalid = assertIs<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("UNSUPPORTED_PROTOCOL_VERSION", invalid.error.code)
  }

  @Test
  fun rejectsResultWithoutPayload() {
    val result = OpenClawGatewayState.parseAndValidateMessage(
      """{"type":"RESULT","nodeId":"n1"}""",
      currentNodeId = null
    )
    val invalid = assertIs<OpenClawWsValidationResult.Invalid>(result)
    assertEquals("MISSING_PAYLOAD", invalid.error.code)
  }

  @Test
  fun acceptsHelloWithDefaultProtocolVersion() {
    val result = OpenClawGatewayState.parseAndValidateMessage(
      """{"type":"HELLO","nodeId":" node-1 "}""",
      currentNodeId = null
    )
    val valid = assertIs<OpenClawWsValidationResult.Valid>(result)
    assertEquals("HELLO", valid.message.type)
    assertEquals("node-1", valid.message.nodeId)
    assertEquals(1, valid.message.protocolVersion)
  }

  @Test
  fun acceptsResultWithPriorHelloIdentity() {
    val result = OpenClawGatewayState.parseAndValidateMessage(
      """{"type":"RESULT","payload":"ok"}""",
      currentNodeId = "node-1"
    )
    val valid = assertIs<OpenClawWsValidationResult.Valid>(result)
    assertEquals("RESULT", valid.message.type)
    assertEquals("ok", valid.message.payload)
  }
}
