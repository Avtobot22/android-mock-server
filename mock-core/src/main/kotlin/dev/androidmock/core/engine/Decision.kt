package dev.androidmock.core.engine

import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.rule.RuleId

sealed interface MockFailure {
	data object InvalidRequest : MockFailure
	data object NoMatchingRule : MockFailure
	class CallbackFailure(val cause: Exception) : MockFailure
	data object InvalidResponse : MockFailure
}

sealed interface Decision {
	data class Mock(val response: ResponseSpec, val ruleId: RuleId) : Decision
	data class Fail(val error: MockFailure) : Decision
}

class ConfigurationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
