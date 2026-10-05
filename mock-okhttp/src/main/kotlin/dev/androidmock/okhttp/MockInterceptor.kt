package dev.androidmock.okhttp

import dev.androidmock.core.engine.Decision
import dev.androidmock.core.engine.MockFailure
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.BodyUnavailableReason
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.InvalidRequestException
import dev.androidmock.core.request.RequestBodySnapshot
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private const val DEFAULT_MAX_REQUEST_BYTES = 1024L * 1024
private const val FAILURE_MESSAGE_PREFIX = "Mock request failed: "
private const val CALL_CANCELLED_MESSAGE = "Mock call cancelled"
private const val DELAY_INTERRUPTED_MESSAGE = "Mock delay interrupted"
private const val CAPTURE_LIMIT_EXCEEDED_MESSAGE = "Request body capture limit exceeded"
private const val MOCK_RESPONSE_MESSAGE = "Mock"
private const val HEAD_METHOD = "HEAD"
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val CANCELLATION_POLL_INTERVAL_MILLIS = 20L

class MockIOException(val failure: MockFailure) : IOException("$FAILURE_MESSAGE_PREFIX${failure.javaClass.simpleName}")

enum class RequestBodyCapture { NONE,
	REPEATABLE
}

class MockInterceptor(
	private val engine: RuleEngine,
	private val bodyCapture: RequestBodyCapture = RequestBodyCapture.NONE,
	private val maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES,
) : Interceptor {

	init {
		require(maxRequestBytes > 0)
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (chain.call().isCanceled()) throw InterruptedIOException(CALL_CANCELLED_MESSAGE)
		val body = request.body
		val snapshotBody = when {
			body == null                           -> RequestBodySnapshot.Absent
			body.isOneShot()                       -> RequestBodySnapshot.Unavailable(BodyUnavailableReason.ONE_SHOT)
			body.isDuplex()                        -> RequestBodySnapshot.Unavailable(BodyUnavailableReason.STREAMING)
			bodyCapture == RequestBodyCapture.NONE -> RequestBodySnapshot.Unavailable(BodyUnavailableReason.UNSUPPORTED)

			else                                   -> {
				val length = body.contentLength()
				if (length < 0) RequestBodySnapshot.Unavailable(BodyUnavailableReason.STREAMING)
				else if (length > maxRequestBytes) RequestBodySnapshot.Unavailable(BodyUnavailableReason.TOO_LARGE)
				else {
					val buffer = Buffer()
					var written = 0L
					val sink = object : ForwardingSink(buffer) {
						override fun write(source: Buffer, byteCount: Long) {
							if (byteCount > maxRequestBytes - written) throw CaptureLimitExceeded()
							super.write(source, byteCount)
							written += byteCount
						}
					}.buffer()
					try {
						body.writeTo(sink)
						sink.flush()
						RequestBodySnapshot.Buffered(buffer.readByteArray())
					} catch (_: CaptureLimitExceeded) {
						RequestBodySnapshot.Unavailable(BodyUnavailableReason.TOO_LARGE)
					}
				}
			}
		}
		val snapshot = try {
			RequestSnapshots.fromUrl(
				request.method, request.url.toString(),
				(0 until request.headers.size).map { HeaderEntry(request.headers.name(it), request.headers.value(it)) },
				snapshotBody
			)
		} catch (_: InvalidRequestException) {
			throw MockIOException(MockFailure.InvalidRequest)
		}
		return when (val decision = engine.decide(snapshot)) {
			is Decision.Fail -> throw MockIOException(decision.error)

			is Decision.Mock -> {
				waitForDelay(chain, decision.response.delay)
				if (chain.call().isCanceled()) throw InterruptedIOException(CALL_CANCELLED_MESSAGE)
				val response = decision.response
				val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
					.code(response.status).message(MOCK_RESPONSE_MESSAGE)
				response.headers.entries.forEach { builder.addHeader(it.name, it.value) }
				val bytes = if (request.method == HEAD_METHOD || response.status == HTTP_NO_CONTENT || response.status == HTTP_NOT_MODIFIED)
					ByteArray(0) else (response.body as? ResponseBody.Bytes)?.bytes ?: ByteArray(0)
				builder.body(bytes.toResponseBody(null))
				builder.build()
			}
		}
	}

	private fun waitForDelay(chain: Interceptor.Chain, delay: Duration) {
		val nanos = delay.inWholeNanoseconds
		var remaining = nanos / NANOS_PER_MILLISECOND + if (nanos % NANOS_PER_MILLISECOND == 0L) 0 else 1
		while (remaining > 0) {
			if (chain.call().isCanceled()) throw InterruptedIOException(CALL_CANCELLED_MESSAGE)
			val slice = minOf(remaining, CANCELLATION_POLL_INTERVAL_MILLIS)
			try {
				TimeUnit.MILLISECONDS.sleep(slice)
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				throw InterruptedIOException(DELAY_INTERRUPTED_MESSAGE).apply { initCause(e) }
			}
			remaining -= slice
		}
	}
}

private class CaptureLimitExceeded : IOException(CAPTURE_LIMIT_EXCEEDED_MESSAGE)
