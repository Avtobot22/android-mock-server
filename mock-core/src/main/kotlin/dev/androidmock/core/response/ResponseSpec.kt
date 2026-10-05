package dev.androidmock.core.response

import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.HeadersSnapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

sealed interface ResponseBody {
	data object Empty : ResponseBody
	class Bytes(bytes: ByteArray) : ResponseBody {

		private val owned = bytes.copyOf()
		val bytes: ByteArray get() = owned.copyOf()
		internal val size: Int get() = owned.size
	}
}

class ResponseSpec(
	val status: Int = STATUS_OK,
	headers: List<HeaderEntry> = emptyList(),
	body: ResponseBody = ResponseBody.Empty,
	val delay: Duration = ZERO,
) {

	val headers = HeadersSnapshot(headers)
	val body: ResponseBody = when (body) {
		ResponseBody.Empty    -> ResponseBody.Empty
		is ResponseBody.Bytes -> ResponseBody.Bytes(body.bytes)
	}
}
