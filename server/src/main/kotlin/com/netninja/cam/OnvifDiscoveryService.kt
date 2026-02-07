package com.netninja.cam

import cam.CameraDevice
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

class OnvifDiscoveryService(
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

    return results.distinctBy { it.xaddr }
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
    val xaddrMatches = Regex("<XAddrs>(.*?)</XAddrs>", RegexOption.DOT_MATCHES_ALL)
      .findAll(payload)
      .flatMap { match ->
        match.groupValues[1].trim().split(Regex("\\s+")).asSequence()
      }
      .filter { it.isNotBlank() }
      .toList()

    val nameFromScope = Regex("onvif://www\\.onvif\\.org/name/([^\\s<]+)")
      .find(payload)
      ?.groupValues
      ?.getOrNull(1)
      ?.replace("_", " ")

    return xaddrMatches.mapNotNull { xaddr ->
      val ip = extractIp(xaddr) ?: return@mapNotNull null
      val name = nameFromScope ?: "ONVIF Camera $ip"
      CameraDevice(name = name, xaddr = xaddr, ip = ip)
    }
  }

  private fun extractIp(xaddr: String): String? {
    val uriHost = runCatching { URI(xaddr).host }.getOrNull()
    if (!uriHost.isNullOrBlank()) return uriHost
    return Regex("""https?://([0-9]{1,3}(?:\\.[0-9]{1,3}){3})""")
      .find(xaddr)
      ?.groupValues
      ?.getOrNull(1)
  }

  companion object {
    private const val MULTICAST_ADDRESS = "239.255.255.250"
    private const val MULTICAST_PORT = 3702
  }
}
