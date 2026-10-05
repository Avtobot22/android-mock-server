package dev.androidmock.core.response

import dev.androidmock.core.request.validateHeaders

internal fun validResponse(response: ResponseSpec, maxResponseBytes: Int): Boolean {
	if (response.status !in MIN_RESPONSE_STATUS..MAX_RESPONSE_STATUS || response.delay.isNegative() || !response.delay.isFinite()) return false
	if ((response.status == STATUS_NO_CONTENT || response.status == STATUS_NOT_MODIFIED) && response.body !is ResponseBody.Empty) return false
	if ((response.body as? ResponseBody.Bytes)?.size?.let { it > maxResponseBytes } == true) return false
	return try {
		validateHeaders(response.headers.entries); true
	} catch (_: IllegalArgumentException) {
		false
	}
}

internal fun copyResponse(response: ResponseSpec): ResponseSpec =
	ResponseSpec(response.status, response.headers.entries, response.body, response.delay)
