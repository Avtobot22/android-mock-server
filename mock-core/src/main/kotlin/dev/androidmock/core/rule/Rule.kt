package dev.androidmock.core.rule

import dev.androidmock.core.request.RequestSnapshot
import dev.androidmock.core.request.FRAGMENT_SEPARATOR
import dev.androidmock.core.request.PATH_SEPARATOR
import dev.androidmock.core.request.QUERY_SEPARATOR
import dev.androidmock.core.request.canonicalPath
import java.util.Collections

internal const val DEFAULT_RULE_PRIORITY = 0
private const val BLANK_RULE_ID_MESSAGE = "Rule ID must not be blank"
private const val INVALID_EXACT_PATH_MESSAGE = "Invalid exact path"

@JvmInline
value class RuleId(val value: String) {
	init {
		require(value.isNotBlank()) { BLANK_RULE_ID_MESSAGE }
	}
}

class ExactPath(path: String) {

	val encodedPath: String

	init {
		require(path.startsWith(PATH_SEPARATOR) && QUERY_SEPARATOR !in path && FRAGMENT_SEPARATOR !in path) { INVALID_EXACT_PATH_MESSAGE }
		encodedPath = canonicalPath(path)
	}

	override fun equals(other: Any?): Boolean = other is ExactPath && encodedPath == other.encodedPath
	override fun hashCode(): Int = encodedPath.hashCode()
}

fun interface RequestMatcher {

	fun matches(request: RequestSnapshot): Boolean
}

class Rule(
	val id: RuleId,
	val path: ExactPath,
	val priority: Int = DEFAULT_RULE_PRIORITY,
	val matcher: RequestMatcher = RequestMatcher { true },
	val responder: Responder,
	tags: Set<String> = emptySet(),
) {

	val tags: Set<String> = Collections.unmodifiableSet(LinkedHashSet(tags))
}
