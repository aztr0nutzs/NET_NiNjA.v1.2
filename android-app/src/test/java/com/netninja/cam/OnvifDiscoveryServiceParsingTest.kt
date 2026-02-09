package com.netninja.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnvifDiscoveryServiceParsingTest {
  @Test
  fun parseProbeMatches_extractsXAddrsAndHosts() {
    val payload =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope">
        <e:Body>
          <d:ProbeMatches xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
            <d:ProbeMatch>
              <d:XAddrs>
                http://192.168.1.20/onvif/device_service
                http://192.168.1.21/onvif/device_service
              </d:XAddrs>
            </d:ProbeMatch>
            <d:ProbeMatch>
              <d:XAddrs>not-a-url</d:XAddrs>
            </d:ProbeMatch>
          </d:ProbeMatches>
        </e:Body>
      </e:Envelope>
      """.trimIndent()

    val devices = OnvifDiscoveryService.parseProbeMatches(payload)

    assertEquals(2, devices.size)
    assertTrue(devices.any { it.ip == "192.168.1.20" && it.xaddr.contains("192.168.1.20") && it.name.contains("192.168.1.20") })
    assertTrue(devices.any { it.ip == "192.168.1.21" && it.xaddr.contains("192.168.1.21") && it.name.contains("192.168.1.21") })
  }
}

