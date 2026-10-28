package dev.androidmock.okhttp.server

import dev.androidmock.core.engine.MockFailure
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse

/** Reserved diagnostics for this debug transport; these are not backend HTTP errors. */
object MockServerFailureProtocol {
    const val STATUS_CODE = 599
    const val HEADER_NAME = "X-Android-Mock-Failure"

    private val failureNames = setOf("NoMatchingRule", "InvalidRequest", "CallbackFailure", "InvalidResponse")

    /** Returns a recognized diagnostic only when both status and the unique marker match. */
    fun failureName(response: Response): String? {
        if (response.code != STATUS_CODE) return null
        val names = response.headers.values(HEADER_NAME)
        return names.singleOrNull()?.takeIf { it in failureNames }
    }

    internal fun response(failure: MockFailure): MockResponse {
        val name = when (failure) {
            MockFailure.NoMatchingRule -> "NoMatchingRule"
            MockFailure.InvalidRequest -> "InvalidRequest"
            is MockFailure.CallbackFailure -> "CallbackFailure"
            MockFailure.InvalidResponse -> "InvalidResponse"
        }
        return MockResponse()
            .setResponseCode(STATUS_CODE)
            .setHeader(HEADER_NAME, name)
            .setHeader("Content-Type", "text/plain; charset=utf-8")
            .setBody("Android mock failure: $name")
    }
}
