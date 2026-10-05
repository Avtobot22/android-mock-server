package dev.androidmock.core.dsl

import dev.androidmock.core.engine.ConfigurationException
import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.rule.RoundRobinResponder
import dev.androidmock.core.rule.ScenarioKey

private const val EMPTY_ROUND_ROBIN_MESSAGE = "Round robin requires responses"

@MockDsl
class RoundRobinDsl internal constructor(private val assets: BodyAssetResolver?) {

	private val responses = mutableListOf<ResponseSpec>()
	private var key: ScenarioKey? = null
	fun scenarioKey(value: ScenarioKey.Shared) {
		key = value
	}

	fun response(block: ResponseDsl.() -> Unit) {
		responses += ResponseDsl(assets).apply(block).build()
	}

	internal fun build(): RoundRobinResponder {
		if (responses.isEmpty()) throw ConfigurationException(EMPTY_ROUND_ROBIN_MESSAGE)
		return RoundRobinResponder(responses, key)
	}
}
