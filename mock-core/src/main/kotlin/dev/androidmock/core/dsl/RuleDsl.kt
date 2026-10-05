package dev.androidmock.core.dsl

import dev.androidmock.core.engine.ConfigurationException
import dev.androidmock.core.request.RequestSnapshot
import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.rule.DynamicResponder
import dev.androidmock.core.rule.ExactPath
import dev.androidmock.core.rule.Responder
import dev.androidmock.core.rule.ResponderContext
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.RuleId
import dev.androidmock.core.rule.StaticResponder
import dev.androidmock.core.rule.DEFAULT_RULE_PRIORITY

private const val MULTIPLE_RESPONDERS_MESSAGE_PREFIX = "Multiple responders for rule "
private const val MISSING_RESPONDER_MESSAGE_PREFIX = "Missing responder for rule "

@MockDsl
class RuleDsl internal constructor(
	private val id: RuleId, private val path: ExactPath, private val assets: BodyAssetResolver?,
) {

	private var priority = DEFAULT_RULE_PRIORITY
	private val conditions = mutableListOf<(RequestSnapshot) -> Boolean>()
	private var responder: Responder? = null
	fun priority(value: Int) {
		priority = value
	}

	fun match(block: MatchDsl.() -> Unit) {
		conditions += MatchDsl().apply(block).conditions
	}

	fun matching(block: (RequestSnapshot) -> Boolean) {
		conditions += block
	}

	fun respond(block: ResponseDsl.() -> Unit) {
		setResponder(StaticResponder(ResponseDsl(assets).apply(block).build()))
	}

	fun roundRobin(block: RoundRobinDsl.() -> Unit) {
		setResponder(RoundRobinDsl(assets).apply(block).build())
	}

	fun respondWith(block: (RequestSnapshot, ResponderContext) -> ResponseSpec) {
		setResponder(DynamicResponder(block))
	}

	private fun setResponder(value: Responder) {
		if (responder != null) throw ConfigurationException("$MULTIPLE_RESPONDERS_MESSAGE_PREFIX${id.value}")
		responder = value
	}

	internal fun build(): Rule {
		val actual = responder ?: throw ConfigurationException("$MISSING_RESPONDER_MESSAGE_PREFIX${id.value}")
		val terms = conditions.toList()
		return Rule(id, path, priority, { request ->
			terms.all { it(request) }
		}, actual)
	}
}
