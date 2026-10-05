package dev.androidmock.core.rule

import dev.androidmock.core.request.RequestSnapshot
import dev.androidmock.core.response.ResponseSpec
import java.util.Collections

fun interface Responder {

	fun respond(request: RequestSnapshot, context: ResponderContext): ResponseSpec
}

class StaticResponder(val response: ResponseSpec) : Responder {

	override fun respond(request: RequestSnapshot, context: ResponderContext) = response
}

class DynamicResponder(private val callback: (RequestSnapshot, ResponderContext) -> ResponseSpec) : Responder {

	override fun respond(request: RequestSnapshot, context: ResponderContext) = callback(request, context)
}

class RoundRobinResponder(responses: List<ResponseSpec>, val scenarioKey: ScenarioKey? = null) : Responder {

	val responses: List<ResponseSpec> = Collections.unmodifiableList(ArrayList(responses))

	init {
		require(responses.isNotEmpty())
	}

	override fun respond(request: RequestSnapshot, context: ResponderContext): ResponseSpec {
		val slot = context.nextSlot(scenarioKey ?: ScenarioKey.PerRule(context.ruleId))
		return responses[(slot % responses.size).toInt()]
	}
}
