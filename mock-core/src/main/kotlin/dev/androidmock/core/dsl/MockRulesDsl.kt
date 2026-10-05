package dev.androidmock.core.dsl

import dev.androidmock.core.engine.ConfigurationException
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.engine.DUPLICATE_RULE_ID_MESSAGE
import dev.androidmock.core.rule.ExactPath
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.RuleId
import java.util.concurrent.CancellationException

private const val INVALID_RULE_ID_MESSAGE_PREFIX = "Invalid rule ID: "

@DslMarker
annotation class MockDsl
fun interface BodyAssetResolver {

	fun read(path: String): ByteArray
}

fun mockRules(bodyAssets: BodyAssetResolver? = null, block: RulesDsl.() -> Unit): List<Rule> =
	RulesDsl(bodyAssets).apply(block).build()

@MockDsl
class RulesDsl internal constructor(private val assets: BodyAssetResolver?) {

	private val rules = mutableListOf<Rule>()
	fun rule(id: String, path: String, block: RuleDsl.() -> Unit) {
		try {
			rules += RuleDsl(RuleId(id), ExactPath(path), assets).apply(block).build()
		} catch (e: CancellationException) {
			throw e
		} catch (e: ConfigurationException) {
			throw e
		} catch (e: Exception) {
			throw ConfigurationException("$INVALID_RULE_ID_MESSAGE_PREFIX$id", e)
		}
	}

	internal fun build(): List<Rule> {
		if (rules.map { it.id }.toSet().size != rules.size) throw ConfigurationException(DUPLICATE_RULE_ID_MESSAGE)
		RuleEngine().replaceRules(rules)
		return rules.toList()
	}
}
