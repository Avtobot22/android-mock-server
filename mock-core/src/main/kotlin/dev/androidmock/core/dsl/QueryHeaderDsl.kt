package dev.androidmock.core.dsl

import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.QueryEntry
import dev.androidmock.core.request.RequestSnapshot
import dev.androidmock.core.request.validateHeaders

@MockDsl
class QueryDsl internal constructor() {

	internal val conditions = mutableListOf<(RequestSnapshot) -> Boolean>()
	fun containsValue(name: String, value: String?) {
		conditions += { request -> request.url.query.any { it.name == name && it.value == value } }
	}

	fun allValues(name: String, value: String?) {
		conditions += { request ->
			val matches = request.url.query.filter { it.name == name }
			matches.isNotEmpty() && matches.all { it.value == value }
		}
	}

	fun exactList(vararg entries: QueryEntry) {
		val expected = entries.toList()
		conditions += { it.url.query == expected }
	}
}

@MockDsl
class HeaderMatchDsl internal constructor() {

	internal val conditions = mutableListOf<(RequestSnapshot) -> Boolean>()
	fun contains(name: String, value: String) {
		validateHeaders(listOf(HeaderEntry(name, value)))
		conditions += { request -> request.headers.values(name).any { it == value } }
	}
}
