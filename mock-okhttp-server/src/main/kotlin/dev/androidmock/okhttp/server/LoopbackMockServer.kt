package dev.androidmock.okhttp.server

import dev.androidmock.core.engine.Decision
import dev.androidmock.core.engine.MockFailure
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.BodyUnavailableReason
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.InvalidRequestException
import dev.androidmock.core.request.RequestBodySnapshot
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import dev.androidmock.core.response.ResponseSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private const val DEFAULT_MAX_REQUEST_BYTES = 1024L * 1024
private const val LOOPBACK_HOST = "127.0.0.1"
private const val HEAD_METHOD = "HEAD"
private const val STATUS_NO_CONTENT = 204
private const val STATUS_NOT_MODIFIED = 304
private const val CONTENT_LENGTH = "Content-Length"
private const val TRANSFER_ENCODING = "Transfer-Encoding"
private const val TRAILER = "Trailer"

/**
 * Opt-in HTTP transport for native OkHttp follow-ups. Only binds IPv4 loopback on a dynamic port.
 *
 * [logicalBaseUrl] is an optional HTTP(S) origin (root path, no credentials/query/fragment).
 * Only its scheme, host and port replace the physical origin seen by the engine; request paths,
 * queries and actual HTTP headers are retained. The server never proxies an unmatched request.
 *
 * Received bodies are captured up to [maxRequestBytes]. Larger bodies become TOO_LARGE and zero
 * received bytes become Absent. No client or engine is owned or shut down by this class.
 * Start/close perform blocking I/O and must run off Android's main thread.
 */
class LoopbackMockServer(
    private val engine: RuleEngine,
    logicalBaseUrl: String? = null,
    private val maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES,
) : AutoCloseable {
    private enum class State { NEW, STARTED, CLOSING, CLOSED }

    private val lifecycleLock = Any()
    private val delayLock = Object()
    private val closeCompleted = CountDownLatch(1)
    private var state = State.NEW
    @Volatile private var closed = false
    @Volatile private var endpoint: HttpUrl? = null
    private val logicalOrigin = logicalBaseUrl?.let(::validateLogicalOrigin)
    private val server = MockWebServer()
    private var requestCleanup: RequestQueueDrainer? = null

    init {
        require(maxRequestBytes in 1..Int.MAX_VALUE.toLong()) { "maxRequestBytes must be in 1..Int.MAX_VALUE" }
        server.bodyLimit = maxRequestBytes
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.dispatcher = object : Dispatcher() {
            override fun peek(): MockResponse = MockResponse().setSocketPolicy(SocketPolicy.EXPECT_CONTINUE)

            override fun dispatch(request: RecordedRequest): MockResponse {
                if (closed) return disconnectedResponse()
                val snapshot = try {
                    val method = request.method ?: throw InvalidRequestException()
                    val path = request.path ?: throw InvalidRequestException()
                    val origin = logicalOrigin ?: endpoint?.toString()?.removeSuffix("/")
                        ?: throw InvalidRequestException()
                    RequestSnapshots.fromUrl(
                        method = method,
                        originalUrl = origin + path,
                        headers = (0 until request.headers.size).map {
                            HeaderEntry(request.headers.name(it), request.headers.value(it))
                        },
                        body = when {
                            request.bodySize == 0L -> RequestBodySnapshot.Absent
                            request.bodySize > maxRequestBytes -> RequestBodySnapshot.Unavailable(BodyUnavailableReason.TOO_LARGE)
                            else -> RequestBodySnapshot.Buffered(request.body.readByteArray())
                        },
                    )
                } catch (_: InvalidRequestException) {
                    return failureResponse(MockFailure.InvalidRequest, request.method)
                }
                return when (val decision = engine.decide(snapshot)) {
                    is Decision.Fail -> failureResponse(decision.error, snapshot.method)
                    is Decision.Mock -> {
                        if (!awaitDelay(decision.response.delay)) disconnectedResponse()
                        else response(decision.response, snapshot.method)
                    }
                }
            }
        }
    }

    /** Available only between successful [start] and [close]. */
    val baseUrl: HttpUrl
        get() = synchronized(lifecycleLock) {
            check(state == State.STARTED) { "Mock server is not started or has been closed" }
            checkNotNull(endpoint)
        }

    /** Starts exactly once; a closed instance cannot be restarted. */
    fun start() = synchronized(lifecycleLock) {
        check(state == State.NEW) { "Mock server can only be started once" }
        try {
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            endpoint = "http://$LOOPBACK_HOST:${server.port}/".toHttpUrl()
            TransportLogFilter.register(server.port)
            requestCleanup = RequestQueueDrainer(server, server.port).also { it.start() }
            state = State.STARTED
        } catch (failure: Exception) {
            state = State.CLOSED
            closed = true
            try {
                shutdownServer()
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            } finally {
                requestCleanup?.close()
                endpoint?.let { TransportLogFilter.unregister(it.port) }
                closeCompleted.countDown()
            }
            throw failure
        }
    }

    /** Idempotent and terminal. Wakes delayed responses and closes active sockets before returning. */
    override fun close() {
        val shutdownOwner = synchronized(lifecycleLock) {
            when (state) {
                State.CLOSED -> return
                State.CLOSING -> false
                State.NEW -> {
                    state = State.CLOSED
                    closed = true
                    closeCompleted.countDown()
                    return
                }
                State.STARTED -> {
                    state = State.CLOSING
                    synchronized(delayLock) {
                        closed = true
                        delayLock.notifyAll()
                    }
                    true
                }
            }
        }
        if (!shutdownOwner) {
            awaitCloseCompletion()
            return
        }
        // Callbacks may read baseUrl: never hold lifecycleLock while waiting for dispatchers.
        try {
            shutdownServer()
        } finally {
            try {
                requestCleanup?.close()
                TransportLogFilter.unregister(checkNotNull(endpoint).port)
            } finally {
                synchronized(lifecycleLock) { state = State.CLOSED }
                closeCompleted.countDown()
            }
        }
    }

    private fun shutdownServer() {
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                try {
                    server.shutdown()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun awaitCloseCompletion() {
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                try {
                    closeCompleted.await()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun awaitDelay(delay: Duration): Boolean = synchronized(delayLock) {
        var remaining = delay.inWholeNanoseconds
        var last = System.nanoTime()
        while (!closed && remaining > 0) {
            try {
                TimeUnit.NANOSECONDS.timedWait(delayLock, remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized false
            }
            val now = System.nanoTime()
            remaining -= (now - last).coerceAtLeast(0)
            last = now
        }
        !closed
    }

    private fun response(spec: ResponseSpec, method: String): MockResponse {
        val bytes = if (method == HEAD_METHOD || spec.status == STATUS_NO_CONTENT || spec.status == STATUS_NOT_MODIFIED) {
            ByteArray(0)
        } else {
            (spec.body as? ResponseBody.Bytes)?.bytes ?: ByteArray(0)
        }
        val result = MockResponse().setResponseCode(spec.status).setBody(Buffer().write(bytes))
        spec.headers.entries.forEach {
            if (!it.name.equals(CONTENT_LENGTH, true) && !it.name.equals(TRANSFER_ENCODING, true) && !it.name.equals(TRAILER, true)) {
                result.addHeaderLenient(it.name, it.value)
            }
        }
        if (spec.status == STATUS_NO_CONTENT || spec.status == STATUS_NOT_MODIFIED) {
            result.removeHeader(CONTENT_LENGTH)
        } else if (method == HEAD_METHOD) {
            result.setHeader(CONTENT_LENGTH, (spec.body as? ResponseBody.Bytes)?.bytes?.size ?: 0)
        }
        return result
    }

    private fun disconnectedResponse() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    private fun failureResponse(failure: MockFailure, method: String?): MockResponse =
        MockServerFailureProtocol.response(failure).apply {
            if (method == HEAD_METHOD) setBody(Buffer())
        }

    private fun validateLogicalOrigin(value: String): String {
        try {
            val uri = URI(value)
            require(uri.rawPath.orEmpty() in setOf("", "/"))
            require(uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null)
            RequestSnapshots.fromUrl("GET", value)
            return value.toHttpUrl().toString().removeSuffix("/")
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("logicalBaseUrl must be an HTTP(S) origin without credentials, path, query or fragment")
        } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("logicalBaseUrl must be an HTTP(S) origin without credentials, path, query or fragment")
        }
    }
}
