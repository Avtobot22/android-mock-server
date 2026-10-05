package dev.androidmock.core.engine

import dev.androidmock.core.rule.RoundRobinResponder
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.ScenarioKey
import java.util.concurrent.atomic.AtomicLong

internal class ScenarioCounter {

	private val value = AtomicLong()

	fun next(): Long {
		while (true) {
			val current = value.get()
			val next = if (current == Long.MAX_VALUE) 0L else current + 1
			if (value.compareAndSet(current, next)) return current
		}
	}
}

internal data class EngineState(
	val registration: List<Rule>,
	val rules: List<Rule>,
	val counters: Map<ScenarioKey, ScenarioCounter>,
)

internal fun countersFor(
	rules: List<Rule>,
	previous: Map<ScenarioKey, ScenarioCounter> = emptyMap(),
	reset: Set<ScenarioKey> = emptySet(),
): Map<ScenarioKey, ScenarioCounter> = rules.mapNotNull(::scenarioKey).distinct().associateWith { key ->
	if (key in reset) ScenarioCounter() else previous[key] ?: ScenarioCounter()
}

internal fun scenarioKey(rule: Rule): ScenarioKey? =
	(rule.responder as? RoundRobinResponder)?.scenarioKey ?: if (rule.responder is RoundRobinResponder) ScenarioKey.PerRule(rule.id) else null
