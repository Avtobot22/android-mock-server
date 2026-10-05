package dev.androidmock.ktor

import dev.androidmock.core.engine.Decision
import dev.androidmock.core.engine.MockFailure
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.BodyUnavailableReason
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.InvalidRequestException
import dev.androidmock.core.request.RequestBodySnapshot
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.delay
import java.io.IOException

private const val DEFAULT_MAX_REQUEST_BYTES = 1024L * 1024
private const val FAILURE_MESSAGE_PREFIX = "Mock request failed: "

class KtorMockException(val failure: MockFailure) : IOException("$FAILURE_MESSAGE_PREFIX${failure.javaClass.simpleName}")

/** Installs the rules as the client's only transport. Every request is answered or failed locally. */
fun mockKtorEngine(engine: RuleEngine, maxRequestBytes: Long = DEFAULT_MAX_REQUEST_BYTES): MockEngine {
	require(maxRequestBytes > 0)
	return MockEngine { request ->
		val body = when (val content = request.body) {
			is OutgoingContent.NoContent                                                  -> RequestBodySnapshot.Absent

			is OutgoingContent.ByteArrayContent                                           -> {
				val length = content.contentLength
				if (length != null && length > maxRequestBytes) RequestBodySnapshot.Unavailable(BodyUnavailableReason.TOO_LARGE)
				else {
					val bytes = content.bytes()
					if (bytes.size > maxRequestBytes) RequestBodySnapshot.Unavailable(BodyUnavailableReason.TOO_LARGE)
					else RequestBodySnapshot.Buffered(bytes)
				}
			}

			is OutgoingContent.ReadChannelContent, is OutgoingContent.WriteChannelContent ->
				RequestBodySnapshot.Unavailable(BodyUnavailableReason.STREAMING)

			else                                                                          -> RequestBodySnapshot.Unavailable(BodyUnavailableReason.UNSUPPORTED)
		}
		val snapshot = try {
			RequestSnapshots.fromUrl(
				request.method.value, request.url.toString(),
				request.headers.entries().flatMap { entry -> entry.value.map { HeaderEntry(entry.key, it) } }, body
			)
		} catch (_: InvalidRequestException) {
			throw KtorMockException(MockFailure.InvalidRequest)
		}
		when (val decision = engine.decide(snapshot)) {
			is Decision.Fail -> throw KtorMockException(decision.error)

			is Decision.Mock -> {
				delay(decision.response.delay)
				val response = decision.response
				val headers = Headers.build {
					response.headers.entries.forEach { append(it.name, it.value) }
				}
				val bytes = if (request.method == HttpMethod.Head) ByteArray(0)
				else (response.body as? ResponseBody.Bytes)?.bytes ?: ByteArray(0)
				respond(bytes, HttpStatusCode.fromValue(response.status), headers)
			}
		}
	}
}
