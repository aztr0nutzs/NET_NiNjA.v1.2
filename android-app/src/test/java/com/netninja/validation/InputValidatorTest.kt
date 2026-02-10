package com.netninja.validation

import org.junit.Assert.*
import org.junit.Test

class InputValidatorTest {

  @Test
  fun validateCidr_validCidr_returnsSuccess() {
    val result = InputValidator.validateCidr("192.168.1.0/24")
    assertTrue(result.valid)
    assertNull(result.error)
  }

  @Test
  fun validateCidr_invalidFormat_returnsFailure() {
    val result = InputValidator.validateCidr("192.168.1.0")
    assertFalse(result.valid)
    assertNotNull(result.error)
  }

  @Test
  fun validateCidr_invalidPrefix_returnsFailure() {
    val result = InputValidator.validateCidr("192.168.1.0/33")
    assertFalse(result.valid)
    assertTrue(result.error!!.contains("prefix"))
  }

  @Test
  fun validateCidr_emptyString_returnsFailure() {
    val result = InputValidator.validateCidr("")
    assertFalse(result.valid)
  }

  @Test
  fun validateIpAddress_validIp_returnsSuccess() {
    val result = InputValidator.validateIpAddress("192.168.1.1")
    assertTrue(result.valid)
  }

  @Test
  fun validateIpAddress_invalidIp_returnsFailure() {
    val result = InputValidator.validateIpAddress("999.999.999.999")
    assertFalse(result.valid)
  }

  @Test
  fun validateMacAddress_validMac_returnsSuccess() {
    val result = InputValidator.validateMacAddress("AA:BB:CC:DD:EE:FF")
    assertTrue(result.valid)
  }

  @Test
  fun validateMacAddress_validMacWithDashes_returnsSuccess() {
    val result = InputValidator.validateMacAddress("AA-BB-CC-DD-EE-FF")
    assertTrue(result.valid)
  }

  @Test
  fun validateMacAddress_invalidMac_returnsFailure() {
    val result = InputValidator.validateMacAddress("invalid")
    assertFalse(result.valid)
  }

  @Test
  fun validateTimeout_validTimeout_returnsSuccess() {
    val result = InputValidator.validateTimeout(300)
    assertTrue(result.valid)
  }

  @Test
  fun validateTimeout_negativeTimeout_returnsFailure() {
    val result = InputValidator.validateTimeout(-1)
    assertFalse(result.valid)
    assertTrue(result.error!!.contains("negative"))
  }

  @Test
  fun validateTimeout_tooLarge_returnsFailure() {
    val result = InputValidator.validateTimeout(99999)
    assertFalse(result.valid)
    assertTrue(result.error!!.contains("too large"))
  }

  @Test
  fun validateUrl_validHttpUrl_returnsSuccess() {
    val result = InputValidator.validateUrl("http://example.com")
    assertTrue(result.valid)
  }

  @Test
  fun validateUrl_validHttpsUrl_returnsSuccess() {
    val result = InputValidator.validateUrl("https://example.com")
    assertTrue(result.valid)
  }

  @Test
  fun validateUrl_localhostBlocked_returnsFailure() {
    val result = InputValidator.validateUrl("http://localhost:8080")
    assertFalse(result.valid)
    assertTrue(result.error!!.contains("Localhost"))
  }

  @Test
  fun validateUrl_invalidProtocol_returnsFailure() {
    val result = InputValidator.validateUrl("ftp://example.com")
    assertFalse(result.valid)
    assertTrue(result.error!!.contains("HTTP/HTTPS"))
  }

  @Test
  fun validatePort_validPort_returnsSuccess() {
    val result = InputValidator.validatePort(8080)
    assertTrue(result.valid)
  }

  @Test
  fun validatePort_tooLow_returnsFailure() {
    val result = InputValidator.validatePort(0)
    assertFalse(result.valid)
  }

  @Test
  fun validatePort_tooHigh_returnsFailure() {
    val result = InputValidator.validatePort(99999)
    assertFalse(result.valid)
  }

  @Test
  fun validatePayloadSize_smallPayload_returnsSuccess() {
    val result = InputValidator.validatePayloadSize("small", 1024)
    assertTrue(result.valid)
  }

  @Test
  fun validatePayloadSize_largePayload_returnsFailure() {
    val payload = "x".repeat(2000)
    val result = InputValidator.validatePayloadSize(payload, 1024)
    assertFalse(result.valid)
    assertTrue(result.error!!.contains("too large"))
  }
}
