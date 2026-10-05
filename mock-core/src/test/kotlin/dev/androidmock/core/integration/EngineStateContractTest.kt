package dev.androidmock.core.integration

import dev.androidmock.core.engine.ConfigurationException
import dev.androidmock.core.engine.Decision
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.rule.ExactPath
import dev.androidmock.core.rule.RoundRobinResponder
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.RuleId
import dev.androidmock.core.rule.ScenarioKey
import dev.androidmock.core.rule.StaticResponder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EngineStateContractTest {

	private fun sequence(id: String, path: String, key: ScenarioKey? = null) = Rule(
		id = RuleId(id),
		path = ExactPath(path),
		responder = RoundRobinResponder(
			listOf(
				ResponseSpec(body = ResponseBody.Bytes("$id-first".toByteArray())),
				ResponseSpec(body = ResponseBody.Bytes("$id-second".toByteArray())),
			),
			key,
		),
	)

	private fun responseText(engine: RuleEngine, path: String): String {
		val request = RequestSnapshots.fromUrl("GET", "https://example.test$path")
		val response = assertIs<Decision.Mock>(engine.decide(request)).response
		return String(assertIs<ResponseBody.Bytes>(response.body).bytes)
	}

	@Test
	fun defaultScenarioKeysKeepRulesIndependent() {
		val engine = RuleEngine().apply { replaceRules(listOf(sequence("read", "/read"), sequence("write", "/write"))) }

		assertEquals("read-first", responseText(engine, "/read"))
		assertEquals("write-first", responseText(engine, "/write"))
		assertEquals("read-second", responseText(engine, "/read"))
		assertEquals("write-second", responseText(engine, "/write"))
	}

	@Test
	fun rejectedUpsertKeepsPublishedRuleAndScenarioPosition() {
		val engine = RuleEngine().apply { replaceRules(listOf(sequence("active", "/x"))) }
		assertEquals("active-first", responseText(engine, "/x"))
		val invalid = Rule(
			id = RuleId("active"),
			path = ExactPath("/x"),
			responder = StaticResponder(ResponseSpec(status = 204, body = ResponseBody.Bytes(byteArrayOf(1)))),
		)

		assertFailsWith<ConfigurationException> { engine.upsertRule(invalid) }

		assertEquals("active-second", responseText(engine, "/x"))
	}

	@Test
	fun replacingWithEquivalentRulesRestartsScenarioFromFirstResponse() {
		val engine = RuleEngine()
		engine.replaceRules(listOf(sequence("active", "/x")))
		assertEquals("active-first", responseText(engine, "/x"))

		engine.replaceRules(listOf(sequence("active", "/x")))

		assertEquals("active-first", responseText(engine, "/x"))
		assertEquals("active-second", responseText(engine, "/x"))
	}

	@Test
	fun removingRuleRestartsItsSharedKeyButPreservesUnrelatedCounter() {
		val shared = ScenarioKey.Shared("checkout")
		val engine = RuleEngine().apply {
			replaceRules(
				listOf(
					sequence("removed", "/removed", shared),
					sequence("remaining", "/remaining", shared),
					sequence("unrelated", "/unrelated"),
				)
			)
		}
		assertEquals("removed-first", responseText(engine, "/removed"))
		assertEquals("unrelated-first", responseText(engine, "/unrelated"))

		assertEquals(true, engine.removeRule(RuleId("removed")))

		assertEquals("remaining-first", responseText(engine, "/remaining"))
		assertEquals("unrelated-second", responseText(engine, "/unrelated"))
	}

	@Test
	fun resetScenariosRestartsEveryCounter() {
		val engine = RuleEngine().apply { replaceRules(listOf(sequence("first", "/first"), sequence("second", "/second"))) }
		assertEquals("first-first", responseText(engine, "/first"))
		assertEquals("second-first", responseText(engine, "/second"))

		engine.resetScenarios()

		assertEquals("first-first", responseText(engine, "/first"))
		assertEquals("second-first", responseText(engine, "/second"))
	}
}
