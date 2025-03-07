package org.thoughtcrime.securesms.dependencies

import android.app.Application
import okhttp3.ConnectionSpec
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.KbsEnclave
import org.thoughtcrime.securesms.net.Network
import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess
import org.thoughtcrime.securesms.push.SignalServiceTrustStore
import org.thoughtcrime.securesms.recipients.LiveRecipientCache
import org.thoughtcrime.securesms.testing.Get
import org.thoughtcrime.securesms.testing.Verb
import org.thoughtcrime.securesms.testing.runSync
import org.thoughtcrime.securesms.testing.success
import org.thoughtcrime.securesms.util.Base64
import org.whispersystems.signalservice.api.KeyBackupService
import org.whispersystems.signalservice.api.SignalServiceAccountManager
import org.whispersystems.signalservice.api.push.TrustStore
import org.whispersystems.signalservice.internal.configuration.SignalCdnUrl
import org.whispersystems.signalservice.internal.configuration.SignalCdsiUrl
import org.whispersystems.signalservice.internal.configuration.SignalKeyBackupServiceUrl
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl
import org.whispersystems.signalservice.internal.configuration.SignalStorageUrl
import org.whispersystems.signalservice.internal.configuration.SignalSvr2Url
import java.security.KeyStore

/**
 * Dependency provider used for instrumentation tests (aka androidTests).
 *
 * Handles setting up a mock web server for API calls, and provides mockable versions of [SignalServiceNetworkAccess] and
 * [KeyBackupService].
 */
class InstrumentationApplicationDependencyProvider(application: Application, default: ApplicationDependencyProvider) : ApplicationDependencies.Provider by default {

  private val serviceTrustStore: TrustStore
  private val uncensoredConfiguration: SignalServiceConfiguration
  private val serviceNetworkAccessMock: SignalServiceNetworkAccess
  private val keyBackupService: KeyBackupService
  private val recipientCache: LiveRecipientCache

  init {
    runSync {
      webServer = MockWebServer()
      baseUrl = webServer.url("").toString()

      addMockWebRequestHandlers(
        Get("/v1/websocket/?login=") {
          MockResponse().success().withWebSocketUpgrade(mockIdentifiedWebSocket)
        },
        Get("/v1/websocket", { !it.path.contains("login") }) {
          MockResponse().success().withWebSocketUpgrade(object : WebSocketListener() {})
        }
      )
    }

    webServer.setDispatcher(object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val handler = handlers.firstOrNull { it.requestPredicate(request) }
        return handler?.responseFactory?.invoke(request) ?: MockResponse().setResponseCode(500)
      }
    })

    serviceTrustStore = SignalServiceTrustStore(application)
    uncensoredConfiguration = SignalServiceConfiguration(
      signalServiceUrls = arrayOf(SignalServiceUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
      signalCdnUrlMap = mapOf(
        0 to arrayOf(SignalCdnUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
        2 to arrayOf(SignalCdnUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT))
      ),
      signalKeyBackupServiceUrls = arrayOf(SignalKeyBackupServiceUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
      signalStorageUrls = arrayOf(SignalStorageUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
      signalCdsiUrls = arrayOf(SignalCdsiUrl(baseUrl, "localhost", serviceTrustStore, ConnectionSpec.CLEARTEXT)),
      signalSvr2Urls = arrayOf(SignalSvr2Url(baseUrl, serviceTrustStore, "localhost", ConnectionSpec.CLEARTEXT)),
      networkInterceptors = emptyList(),
      socketFactory = Network.socketFactory,
      proxySelector = Network.proxySelectorForSocks,
      dns = Network.dns,
      zkGroupServerPublicParams = Base64.decode(BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS),
      genericServerPublicParams = Base64.decode(BuildConfig.GENERIC_SERVER_PUBLIC_PARAMS)
    )

    serviceNetworkAccessMock = mock {
      on { getConfiguration() } doReturn uncensoredConfiguration
      on { getConfiguration(any()) } doReturn uncensoredConfiguration
      on { uncensoredConfiguration } doReturn uncensoredConfiguration
    }

    keyBackupService = mock()

    recipientCache = LiveRecipientCache(application) { r -> r.run() }
  }

  override fun provideSignalServiceNetworkAccess(): SignalServiceNetworkAccess {
    return serviceNetworkAccessMock
  }

  override fun provideKeyBackupService(signalServiceAccountManager: SignalServiceAccountManager, keyStore: KeyStore, enclave: KbsEnclave): KeyBackupService {
    return keyBackupService
  }

  override fun provideRecipientCache(): LiveRecipientCache {
    return recipientCache
  }

  class MockWebSocket : WebSocketListener() {
    private val TAG = "MockWebSocket"

    var webSocket: WebSocket? = null
      private set

    override fun onOpen(webSocket: WebSocket, response: Response) {
      Log.i(TAG, "onOpen(${webSocket.hashCode()})")
      this.webSocket = webSocket
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      Log.i(TAG, "onClosing(${webSocket.hashCode()}): $code, $reason")
      this.webSocket = null
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
      Log.i(TAG, "onClosed(${webSocket.hashCode()}): $code, $reason")
      this.webSocket = null
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      Log.w(TAG, "onFailure(${webSocket.hashCode()})", t)
      this.webSocket = null
    }
  }

  companion object {
    lateinit var webServer: MockWebServer
      private set
    lateinit var baseUrl: String
      private set

    val mockIdentifiedWebSocket = MockWebSocket()

    private val handlers: MutableList<Verb> = mutableListOf()

    fun addMockWebRequestHandlers(vararg verbs: Verb) {
      handlers.addAll(verbs)
    }

    fun injectWebSocketMessage(value: ByteString) {
      mockIdentifiedWebSocket.webSocket!!.send(value)
    }

    fun clearHandlers() {
      handlers.clear()
    }
  }
}
