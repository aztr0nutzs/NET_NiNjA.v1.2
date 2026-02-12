package com.netninja.gateway.g5ar

import com.netninja.network.RetryPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class G5arApiImplTest {

  private lateinit var server: MockWebServer

  @Before
  fun setup() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun teardown() {
    server.shutdown()
  }

  @Test
  fun parseSamples_loginClientsTelemetryWifi() = runBlocking {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return when {
          request.path == "/TMI/v1/auth/login" -> MockResponse().setResponseCode(200)
            .setBody("""{"token":"jwt123"}""")
          request.path == "/TMI/v1/network/telemetry?get=clients" -> MockResponse().setResponseCode(200)
            .setBody("""{"clients":[{"name":"Pixel","ip":"192.168.12.10","mac":"AA:BB","rssi":"-51","interfaceType":"wifi"}]}""")
          request.path == "/TMI/v1/network/telemetry?get=cell" -> MockResponse().setResponseCode(200)
            .setBody("""{"rsrp":"-95","rsrq":"-11","sinr":"15","band":"n41","pci":"22"}""")
<<<<<<< ours
          request.path == "/TMI/v1/gateway?get=signal" -> MockResponse().setResponseCode(200)
            .setBody("""{"status":"CONNECTED","bars":"4"}""")
=======
>>>>>>> theirs
          request.path == "/TMI/v1/network/configuration/v2?get=ap" -> MockResponse().setResponseCode(200)
            .setBody("""{"ssid24":"Home24","pass24":"secret","enabled24":true,"unknownField":"keep"}""")
          else -> MockResponse().setResponseCode(404)
        }
      }
    }

    val api = G5arApiImpl(server.url("/").toString().trimEnd('/'), RetryPolicy(maxAttempts = 1))
    val session = api.login("admin", "pw")
    val clients = api.getClients(session)
<<<<<<< ours
    val signal = api.getGatewaySignal(session)
=======
>>>>>>> theirs
    val cell = api.getCellTelemetry(session)
    val wifi = api.getWifiConfig(session)

    assertEquals("jwt123", session.token)
    assertEquals(1, clients.size)
    assertEquals("Pixel", clients.first().name)
<<<<<<< ours
    assertEquals("CONNECTED", signal.status)
=======
>>>>>>> theirs
    assertEquals("-95", cell.rsrp)
    assertEquals("Home24", wifi.ssid24)
    assertTrue(wifi.raw.containsKey("unknownField"))
  }

  @Test
  fun loginThenAuthorizedClients() = runBlocking {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return when {
          request.path == "/TMI/v1/auth/login" -> MockResponse().setResponseCode(200).setBody("""{"token":"abc"}""")
          request.path == "/TMI/v1/network/telemetry?get=clients" -> {
            val auth = request.getHeader("Authorization")
            if (auth == "Bearer abc") {
              MockResponse().setResponseCode(200).setBody("""[{"name":"Laptop","ip":"192.168.12.20"}]""")
            } else {
              MockResponse().setResponseCode(401)
            }
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
    }

    val api = G5arApiImpl(server.url("/").toString().trimEnd('/'), RetryPolicy(maxAttempts = 1))
    val session = api.login("admin", "pw")
    val clients = api.getClients(session)

    assertEquals(1, clients.size)
    assertEquals("Laptop", clients.first().name)
  }

  @Test
  fun unauthorizedThenReloginRetryWorks() = runBlocking {
    var loginCount = 0
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return when {
          request.path == "/TMI/v1/auth/login" -> {
            loginCount += 1
            MockResponse().setResponseCode(200).setBody("""{"token":"token$loginCount"}""")
          }
          request.path == "/TMI/v1/network/telemetry?get=clients" -> {
            val auth = request.getHeader("Authorization")
            if (auth == "Bearer token1") {
              MockResponse().setResponseCode(401)
            } else {
              MockResponse().setResponseCode(200).setBody("""[{"name":"RetryClient","ip":"192.168.12.30"}]""")
            }
          }
          else -> MockResponse().setResponseCode(404)
        }
      }
    }

    val api = G5arApiImpl(server.url("/").toString().trimEnd('/'), RetryPolicy(maxAttempts = 1))
    val session = api.login("admin", "pw")
    val clients = api.getClients(session)

    assertEquals(2, loginCount)
    assertEquals("RetryClient", clients.first().name)
  }
<<<<<<< ours
=======

  @Test
  fun loginExtractsNestedToken() = runBlocking {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return if (request.path == "/TMI/v1/auth/login") {
          MockResponse().setResponseCode(200).setBody("""{"data":{"auth":{"jwt":"nested-token"}}}""")
        } else {
          MockResponse().setResponseCode(404)
        }
      }
    }

    val api = G5arApiImpl(server.url("/").toString().trimEnd('/'), RetryPolicy(maxAttempts = 1))
    val session = api.login("admin", "pw")
    assertEquals("nested-token", session.token)
  }

  @Test
  fun loginFallsBackToFormEncoded() = runBlocking {
    var loginAttempt = 0
    var sawForm = false
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return if (request.path == "/TMI/v1/auth/login") {
          loginAttempt += 1
          val contentType = request.getHeader("Content-Type").orEmpty()
          if (contentType.startsWith("application/json")) {
            MockResponse().setResponseCode(415).setBody("unsupported media type")
          } else {
            sawForm = true
            MockResponse().setResponseCode(200).setBody("""{"token":"form-token"}""")
          }
        } else {
          MockResponse().setResponseCode(404)
        }
      }
    }

    val api = G5arApiImpl(server.url("/").toString().trimEnd('/'), RetryPolicy(maxAttempts = 1))
    val session = api.login("admin", "pw")

    assertTrue("expected a form login attempt", sawForm)
    assertTrue("expected multiple login payload attempts", loginAttempt > 1)
    assertEquals("form-token", session.token)
  }
>>>>>>> theirs
}
