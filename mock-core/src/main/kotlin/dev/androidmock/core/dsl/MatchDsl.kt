package dev.androidmock.core.dsl

import dev.androidmock.core.request.RequestBodySnapshot
import dev.androidmock.core.request.RequestSnapshot
import dev.androidmock.core.request.HTTP_SCHEME
import dev.androidmock.core.request.HTTPS_SCHEME
import dev.androidmock.core.request.MIN_PORT
import dev.androidmock.core.request.MAX_PORT
import java.util.Locale

@MockDsl
class MatchDsl internal constructor() {

	internal val conditions = mutableListOf<(RequestSnapshot) -> Boolean>()
	fun method(value: String) {
		require(value.isNotBlank())
		val expected = value.uppercase(Locale.ROOT)
		conditions += { it.method == expected }
	}

	fun scheme(value: String) {
		require(value.equals(HTTP_SCHEME, true) || value.equals(HTTPS_SCHEME, true))
		val expected = value.lowercase(Locale.ROOT)
		conditions += { it.url.scheme == expected }
	}

	fun host(value: String) {
		require(value.isNotBlank())
		val expected = value.lowercase(Locale.ROOT)
		conditions += { it.url.host == expected }
	}

	fun port(value: Int) {
		require(value in MIN_PORT..MAX_PORT)
		conditions += { it.url.effectivePort == value }
	}

	fun query(block: QueryDsl.() -> Unit) {
		conditions += QueryDsl().apply(block).conditions
	}

	fun headers(block: HeaderMatchDsl.() -> Unit) {
		conditions += HeaderMatchDsl().apply(block).conditions
	}

	fun bodyExactBytes(value: ByteArray) {
		val expected = value.copyOf()
		conditions += { (it.body as? RequestBodySnapshot.Buffered)?.matches(expected) == true }
	}

	fun bodyContainsBytes(value: ByteArray) {
		val expected = value.copyOf()
		conditions += { (it.body as? RequestBodySnapshot.Buffered)?.contains(expected) == true }
	}

	fun bodyExactUtf8(value: String) = bodyExactBytes(value.toByteArray(Charsets.UTF_8))
	fun bodyContainsUtf8(value: String) = bodyContainsBytes(value.toByteArray(Charsets.UTF_8))
}
