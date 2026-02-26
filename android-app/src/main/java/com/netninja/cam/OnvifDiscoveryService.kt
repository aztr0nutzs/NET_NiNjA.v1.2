package com.netninja.cam

import android.content.Context
import kotlinx.serialization.Serializable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class CameraDevice(
  val name: String,
  val xaddr: String,
  val ip: String
)

class OnvifDiscoveryService(
  @Suppress("UNUSED_PARAMETER") private val ctx: Context? = null,
  private val timeoutMs: Int = 2000,
  private val maxResponses: Int = 32
) {
  fun discover(): List<CameraDevice> {
    val message = buildProbe()
    val address = InetAddress.getByName(MULTICAST_ADDRESS)
    val results = mutableListOf<CameraDevice>()

    DatagramSocket().use { socket ->
      socket.soTimeout = timeoutMs
      socket.send(DatagramPacket(message, message.size, address, MULTICAST_PORT))

      val buffer = ByteArray(8192)
      val deadline = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < deadline && results.size < maxResponses) {
        try {
          val packet = DatagramPacket(buffer, buffer.size)
          socket.receive(packet)
          val payload = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
          results.addAll(parseResponse(payload))
        } catch (_: SocketTimeoutException) {
          break
        }
      }
    }

    return results
      .filter { it.xaddr.isNotBlank() && it.ip.isNotBlank() }
      .distinctBy { it.xaddr }
  }

  private fun buildProbe(): ByteArray {
    val uuid = UUID.randomUUID()
    val body = """
      |<?xml version="1.0" encoding="UTF-8"?>
      |<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
      |  xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
      |  xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
      |  xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
      |  <e:Header>
      |    <w:MessageID>uuid:$uuid</w:MessageID>
      |    <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
      |    <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
      |  </e:Header>
      |  <e:Body>
      |    <d:Probe>
      |      <d:Types>dn:NetworkVideoTransmitter</d:Types>
      |    </d:Probe>
      |  </e:Body>
      |</e:Envelope>
    """.trimMargin()
    return body.toByteArray(StandardCharsets.UTF_8)
  }

  private fun parseResponse(payload: String): List<CameraDevice> {
    return parseProbeMatches(payload)
  }

  private fun extractIp(xaddr: String): String? {
    return runCatching {
      val uri = URI(xaddr)
      uri.host?.takeIf { it.isNotBlank() }
    }.getOrNull()
  }

  companion object {
    private const val MULTICAST_ADDRESS = "239.255.255.250"
    private const val MULTICAST_PORT = 3702

    @JvmStatic
    fun parseProbeMatches(payload: String): List<CameraDevice> {
      val xaddrMatches = Regex("<(?:\\w+:)?XAddrs>(.*?)</(?:\\w+:)?XAddrs>", RegexOption.DOT_MATCHES_ALL)
        .findAll(payload)
        .flatMap { match ->
          match.groupValues[1].trim().split(Regex("\\s+")).asSequence()
        }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()

      return xaddrMatches.mapNotNull { xaddr ->
        val ip = runCatching {
          val uri = URI(xaddr)
          uri.host?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return@mapNotNull null
        CameraDevice(name = "ONVIF Camera $ip", xaddr = xaddr, ip = ip)
      }
    }
  }
}
