package dev.androidmock.core.engine

import dev.androidmock.core.response.ResponseBody
import dev.androidmock.core.response.validResponse
import dev.androidmock.core.rule.RoundRobinResponder
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.StaticResponder
import java.util.Collections

internal const val DUPLICATE_RULE_ID_MESSAGE = "Duplicate rule ID"
private const val INVALID_RESPONSE_MESSAGE_PREFIX = "Invalid response for rule "
private const val TABLE_LIMIT_EXCEEDED_MESSAGE = "Response table exceeds limit"

internal fun prepareRules(rules: List<Rule>, limits: EngineLimits): List<Rule> {
	if (rules.map { it.id }.toSet().size != rules.size) throw ConfigurationException(DUPLICATE_RULE_ID_MESSAGE)
	var bytes = 0L
	rules.forEach { rule ->
		val responses = when (val responder = rule.responder) {
			is StaticResponder -> listOf(responder.response)
			is RoundRobinResponder -> responder.responses
			else -> emptyList()
		}
		responses.forEach { response ->
			if (!validResponse(response, limits.maxResponseBytes)) throw ConfigurationException("$INVALID_RESPONSE_MESSAGE_PREFIX${rule.id.value}")
			bytes += (response.body as? ResponseBody.Bytes)?.size ?: 0
			if (bytes > limits.maxTableBytes) throw ConfigurationException(TABLE_LIMIT_EXCEEDED_MESSAGE)
		}
	}
	return Collections.unmodifiableList(ArrayList(rules))
}

internal fun sortedRules(rules: List<Rule>): List<Rule> = Collections.unmodifiableList(
	rules.withIndex().sortedWith(
		compareByDescending<IndexedValue<Rule>> { it.value.priority }.thenBy { it.index }
	).map { it.value })
