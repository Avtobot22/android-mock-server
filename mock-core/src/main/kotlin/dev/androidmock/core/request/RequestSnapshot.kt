package dev.androidmock.core.request

import java.util.Collections

private const val INVALID_HEADER_MESSAGE = "Invalid header"
private const val CARRIAGE_RETURN = '\r'
private const val LINE_FEED = '\n'

private fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

data class QueryEntry(val name: String, val value: String?)
data class HeaderEntry(val name: String, val value: String)

class HeadersSnapshot(entries: List<HeaderEntry> = emptyList()) {

	val entries: List<HeaderEntry> = frozen(entries)
	fun values(name: String): List<String> = entries.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }
}

class UrlSnapshot internal constructor(
	val scheme: String,
	val host: String,
	val effectivePort: Int,
	val encodedPath: String,
	query: List<QueryEntry>,
) {

	val query: List<QueryEntry> = frozen(query)
}

class RequestSnapshot internal constructor(
	val method: String,
	val originalUrl: String,
	val url: UrlSnapshot,
	val headers: HeadersSnapshot,
	val body: RequestBodySnapshot,
)

internal fun validateHeaders(entries: List<HeaderEntry>) {
	entries.forEach {
		require(it.name.matches(HTTP_TOKEN_PATTERN) && CARRIAGE_RETURN !in it.value && LINE_FEED !in it.value) { INVALID_HEADER_MESSAGE }
	}
}
