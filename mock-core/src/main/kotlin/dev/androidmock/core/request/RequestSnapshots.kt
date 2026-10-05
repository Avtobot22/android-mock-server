package dev.androidmock.core.request

import java.util.Locale

private const val INVALID_REQUEST_MESSAGE = "Invalid HTTP request"

class InvalidRequestException : IllegalArgumentException(INVALID_REQUEST_MESSAGE)

object RequestSnapshots {

	fun fromUrl(
		method: String,
		originalUrl: String,
		headers: List<HeaderEntry> = emptyList(),
		body: RequestBodySnapshot = RequestBodySnapshot.Absent,
	): RequestSnapshot {
		try {
			require(method.matches(HTTP_TOKEN_PATTERN))
			val url = normalizeUrl(originalUrl)
			validateHeaders(headers)
			return RequestSnapshot(
				method.uppercase(Locale.ROOT), originalUrl,
				url, HeadersSnapshot(headers),
				when (body) {
					is RequestBodySnapshot.Buffered -> RequestBodySnapshot.Buffered(body.bytes)
					else                            -> body
				}
			)
		} catch (e: Exception) {
			if (e is InvalidRequestException) throw e
			throw InvalidRequestException()
		}
	}
}
