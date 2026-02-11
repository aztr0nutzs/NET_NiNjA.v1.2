package com.netninja.scan

import com.netninja.Device
import com.netninja.config.ServerConfig
import com.netninja.logging.StructuredLogger
import com.netninja.network.RetryPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network scanning engine extracted from AndroidLocalServer.
 * Addresses RISK-01: Refactor 1903-line file into focused modules.
 */
class ScanEngine(
  private val config: ServerConfig,
  private val logger: StructuredLogger
) {

  private val retryPolicy = RetryPolicy(
    maxAttempts = config.retryMaxAttempts,
    initialDelayMs = config.retryInitialDelayMs,
    maxDelayMs = config.retryMaxDelayMs
  )

  /**
   * Scan a list of IP addresses for reachable devices.
   */
  suspend fun scanIpRange(
    ips: List<String>,
    onProgress: (completed: Int, total: Int, found: Int) -> Unit,
    onDeviceFound: suspend (Device) -> Unit
  ): List<Device> = coroutineScope {
    val sem = Semaphore(config.scanConcurrency)
    val total = ips.size.coerceAtLeast(1)
    val completed = AtomicInteger(0)
    val foundCount = AtomicInteger(0)
    val resultsMutex = Mutex()
    val results = mutableListOf<Device>()

    ips.map { ip ->
      async(Dispatchers.IO) {
        sem.withPermit {
          val device = scanSingleIp(ip)
          if (device != null) {
            resultsMutex.withLock { results.add(device) }
            foundCount.incrementAndGet()
            onDeviceFound(device)
          }

          val done = completed.incrementAndGet()
          onProgress(done, total, foundCount.get())
        }
      }
    }.awaitAll()

    results
  }

  /**
   * Scan a single IP address.
   */
  private suspend fun scanSingleIp(ip: String): Device? {
    val reachable = isReachable(ip)
    if (!reachable) return null

    val hostname = resolveHostname(ip)
    val openPorts = scanPorts(ip)

    return Device(
      id = ip,
      ip = ip,
      name = hostname ?: ip,
      online = true,
      lastSeen = System.currentTimeMillis(),
      mac = null,
      hostname = hostname,
      vendor = null,
      os = guessOs(openPorts),
      openPorts = openPorts
    )
  }

  /**
   * Check if an IP is reachable with retry logic.
   */
  private suspend fun isReachable(ip: String): Boolean {
    return retryPolicy.executeOrNull("isReachable") {
      withContext(Dispatchers.IO) {
        val timeout = config.reachabilityTimeoutMs
        
        // Try ICMP first
        val icmpReachable = InetAddress.getByName(ip).isReachable(timeout)
        if (icmpReachable) return@withContext true

        // Try common ports
        for (port in config.reachabilityProbePorts) {
          try {
            Socket().use { socket ->
              socket.connect(InetSocketAddress(ip, port), timeout)
              return@withContext true
            }
          } catch (e: Exception) {
            // Continue to next port
          }
        }
        false
      }
    } ?: false
  }

  /**
   * Resolve hostname with retry logic.
   */
  private suspend fun resolveHostname(ip: String): String? {
    return retryPolicy.executeOrNull("resolveHostname") {
      withContext(Dispatchers.IO) {
        val host = InetAddress.getByName(ip).canonicalHostName
        if (host == ip) null else host
      }
    }
  }

  /**
   * Scan common ports on an IP address.
   */
  private suspend fun scanPorts(ip: String): List<Int> {
    return withContext(Dispatchers.IO) {
      val openPorts = mutableListOf<Int>()
      val timeout = config.portScanTimeoutMs

      for (port in config.commonPorts) {
        val isOpen = retryPolicy.executeOrNull("portScan") {
          Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), timeout)
            true
          }
        } ?: false

        if (isOpen) {
          openPorts.add(port)
        }
      }

      openPorts
    }
  }

  /**
   * Guess OS based on open ports.
   */
  private fun guessOs(openPorts: List<Int>): String? {
    return when {
      openPorts.contains(445) || openPorts.contains(3389) -> "Windows"
      openPorts.contains(5555) -> "Android"
      openPorts.contains(22) -> "Linux"
      else -> null
    }
  }

  /**
   * Convert CIDR to list of IPs.
   */
  fun cidrToIps(cidr: String): List<String> {
    val parts = cidr.split("/")
    if (parts.size != 2) return emptyList()
    
    val baseIp = parts[0]
    val prefix = parts[1].toIntOrNull() ?: return emptyList()

    val base = ipToInt(baseIp)
    val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
    val network = base and mask

    val hostCount = (1L shl (32 - prefix)).coerceAtMost(1L shl 16).toInt()
    val out = ArrayList<String>(minOf(hostCount, config.maxScanTargets))

    for (i in 1 until hostCount - 1) {
      out += intToIp(network + i)
      if (out.size >= config.maxScanTargets) break
    }
    return out
  }

  private fun ipToInt(ip: String): Int {
    val parts = ip.split(".").map { it.toIntOrNull() ?: return 0 }
    if (parts.size != 4) return 0
    return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
  }

  private fun intToIp(value: Int): String =
    listOf((value ushr 24) and 0xff, (value ushr 16) and 0xff, (value ushr 8) and 0xff, value and 0xff).joinToString(".")
}
