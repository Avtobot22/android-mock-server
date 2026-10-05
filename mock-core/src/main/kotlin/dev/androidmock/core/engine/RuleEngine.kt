package dev.androidmock.core.engine

import dev.androidmock.core.request.RequestSnapshot
import dev.androidmock.core.response.copyResponse
import dev.androidmock.core.response.validResponse
import dev.androidmock.core.rule.ResponderContext
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.RuleId
import java.util.concurrent.CancellationException

private const val DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024
private const val DEFAULT_MAX_TABLE_BYTES = 16L * 1024 * 1024
private const val UNREGISTERED_SCENARIO_MESSAGE = "Unregistered scenario key"

class EngineLimits(
	val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
	val maxTableBytes: Long = DEFAULT_MAX_TABLE_BYTES,
) {

	init {
		require(maxResponseBytes > 0 && maxTableBytes > 0)
	}
}

class RuleEngine(val limits: EngineLimits = EngineLimits()) {

	@Volatile
	private var state = EngineState(emptyList(), emptyList(), emptyMap())

	fun decide(request: RequestSnapshot): Decision {
		val captured = state
		for (rule in captured.rules) {
			if (rule.path.encodedPath != request.url.encodedPath) continue
			val matched = try {
				rule.matcher.matches(request)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				return Decision.Fail(MockFailure.CallbackFailure(e))
			}
			if (!matched) continue
			val response = try {
				rule.responder.respond(request, ResponderContext(rule.id) { key ->
					checkNotNull(captured.counters[key]) { UNREGISTERED_SCENARIO_MESSAGE }.next()
				})
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				return Decision.Fail(MockFailure.CallbackFailure(e))
			}
			if (!validResponse(response, limits.maxResponseBytes)) return Decision.Fail(MockFailure.InvalidResponse)
			return Decision.Mock(copyResponse(response), rule.id)
		}
		return Decision.Fail(MockFailure.NoMatchingRule)
	}

	@Synchronized
	fun replaceRules(rules: List<Rule>) {
		val prepared = prepareRules(rules, limits)
		state = EngineState(prepared, sortedRules(prepared), countersFor(prepared))
	}

	@Synchronized
	fun upsertRule(rule: Rule) {
		val old = state
		val index = old.registration.indexOfFirst { it.id == rule.id }
		val candidate = old.registration.toMutableList()
		if (index >= 0) candidate[index] = rule else candidate.add(rule)
		val prepared = prepareRules(candidate, limits)
		val affected = buildSet {
			if (index >= 0) scenarioKey(old.registration[index])?.let(::add)
			scenarioKey(rule)?.let(::add)
		}
		state = EngineState(prepared, sortedRules(prepared), countersFor(prepared, old.counters, affected))
	}

	@Synchronized
	fun removeRule(id: RuleId): Boolean {
		val old = state
		val removed = old.registration.firstOrNull { it.id == id } ?: return false
		val prepared = prepareRules(old.registration.filterNot { it.id == id }, limits)
		state = EngineState(prepared, sortedRules(prepared), countersFor(prepared, old.counters, setOfNotNull(scenarioKey(removed))))
		return true
	}

	@Synchronized
	fun resetScenarios() {
		val old = state
		state = EngineState(old.registration, old.rules, countersFor(old.registration))
	}
}
