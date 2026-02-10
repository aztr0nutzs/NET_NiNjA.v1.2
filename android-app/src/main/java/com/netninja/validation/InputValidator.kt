package com.netninja.validation

import java.net.InetAddress

/**
 * Input validation for API endpoints.
 * Addresses RISK-05: No Input Validation on API Endpoints
 */
object InputValidator {

  data class ValidationResult(
    val valid: Boolean,
    val error: String? = null
  ) {
    companion object {
      fun success() = ValidationResult(true)
      fun failure(error: String) = ValidationResult(false, error)
    }
  }

  /**
   * Validate CIDR notation (e.g., "192.168.1.0/24")
   */
  fun validateCidr(cidr: String?): ValidationResult {
    if (cidr.isNullOrBlank()) {
      return ValidationResult.failure("CIDR cannot be empty")
    }

    val parts = cidr.split("/")
    if (parts.size != 2) {
      return ValidationResult.failure("Invalid CIDR format. Expected: x.x.x.x/prefix")
    }

    val ip = parts[0]
    val prefix = parts[1].toIntOrNull()

    if (prefix == null || prefix < 0 || prefix > 32) {
      return ValidationResult.failure("Invalid prefix. Must be 0-32")
    }

    return validateIpAddress(ip)
  }

  /**
   * Validate IPv4 address
   */
  fun validateIpAddress(ip: String?): ValidationResult {
    if (ip.isNullOrBlank()) {
      return ValidationResult.failure("IP address cannot be empty")
    }

    try {
      val addr = InetAddress.getByName(ip)
      if (addr.hostAddress != ip) {
        return ValidationResult.failure("Invalid IP address format")
      }
      return ValidationResult.success()
    } catch (e: Exception) {
      return ValidationResult.failure("Invalid IP address: ${e.message}")
    }
  }

  /**
   * Validate MAC address
   */
  fun validateMacAddress(mac: String?): ValidationResult {
    if (mac.isNullOrBlank()) {
      return ValidationResult.failure("MAC address cannot be empty")
    }

    val normalized = mac.replace("-", ":").uppercase()
    val macRegex = "^([0-9A-F]{2}:){5}[0-9A-F]{2}$".toRegex()
    
    return if (macRegex.matches(normalized)) {
      ValidationResult.success()
    } else {
      ValidationResult.failure("Invalid MAC address format")
    }
  }

  /**
   * Validate timeout value
   */
  fun validateTimeout(timeoutMs: Int?): ValidationResult {
    if (timeoutMs == null) {
      return ValidationResult.failure("Timeout cannot be null")
    }

    return when {
      timeoutMs < 0 -> ValidationResult.failure("Timeout cannot be negative")
      timeoutMs > 30000 -> ValidationResult.failure("Timeout too large (max 30000ms)")
      else -> ValidationResult.success()
    }
  }

  /**
   * Validate URL
   */
  fun validateUrl(url: String?): ValidationResult {
    if (url.isNullOrBlank()) {
      return ValidationResult.failure("URL cannot be empty")
    }

    if (url.length > 2048) {
      return ValidationResult.failure("URL too long (max 2048 characters)")
    }

    try {
      val parsed = java.net.URL(url)
      
      if (parsed.protocol !in listOf("http", "https")) {
        return ValidationResult.failure("Only HTTP/HTTPS protocols allowed")
      }

      val host = parsed.host.lowercase()
      if (host in listOf("localhost", "127.0.0.1", "0.0.0.0", "::1")) {
        return ValidationResult.failure("Localhost targets not allowed")
      }

      return ValidationResult.success()
    } catch (e: Exception) {
      return ValidationResult.failure("Invalid URL: ${e.message}")
    }
  }

  /**
   * Validate port number
   */
  fun validatePort(port: Int?): ValidationResult {
    if (port == null) {
      return ValidationResult.failure("Port cannot be null")
    }

    return when {
      port < 1 -> ValidationResult.failure("Port must be >= 1")
      port > 65535 -> ValidationResult.failure("Port must be <= 65535")
      else -> ValidationResult.success()
    }
  }

  /**
   * Validate JSON payload size
   */
  fun validatePayloadSize(payload: String?, maxSizeBytes: Int = 1024 * 1024): ValidationResult {
    if (payload == null) {
      return ValidationResult.success()
    }

    val sizeBytes = payload.toByteArray().size
    return if (sizeBytes > maxSizeBytes) {
      ValidationResult.failure("Payload too large: $sizeBytes bytes (max $maxSizeBytes)")
    } else {
      ValidationResult.success()
    }
  }
}
