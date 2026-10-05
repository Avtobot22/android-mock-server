package dev.androidmock.core.rule

class ResponderContext internal constructor(val ruleId: RuleId, private val next: (ScenarioKey) -> Long) {

	internal fun nextSlot(key: ScenarioKey): Long = next(key)
}

sealed interface ScenarioKey {
	data class PerRule(val ruleId: RuleId) : ScenarioKey
	data class Shared(val name: String) : ScenarioKey {
		init {
			require(name.isNotBlank())
		}
	}
}
