package dev.androidmock.core.integration

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.ConfigurationException
import dev.androidmock.core.engine.Decision
import dev.androidmock.core.engine.MockFailure
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.BodyUnavailableReason
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.InvalidRequestException
import dev.androidmock.core.request.QueryEntry
import dev.androidmock.core.request.RequestBodySnapshot
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.rule.DynamicResponder
import dev.androidmock.core.rule.ExactPath
import dev.androidmock.core.rule.RequestMatcher
import dev.androidmock.core.rule.RoundRobinResponder
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.RuleId
import dev.androidmock.core.rule.ScenarioKey
import dev.androidmock.core.rule.StaticResponder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CoreTest {

	private fun request(path: String = "/x") = RequestSnapshots.fromUrl("get", "https://EXAMPLE.test$path")
	private fun text(decision: Decision): String = String((assertIs<Decision.Mock>(decision).response.body as ResponseBody.Bytes).bytes)

	@Test
	fun normalizationPreservesEncodedPathAndQueryDistinctions() {
		val snapshot = RequestSnapshots.fromUrl("get", "HTTPS://EXAMPLE.test:443/%2f/%41?tag=a&tag=b&flag&empty=&plus=a+b")
		assertEquals("GET", snapshot.method)
		assertEquals("example.test", snapshot.url.host)
		assertEquals(443, snapshot.url.effectivePort)
		assertEquals("/%2F/%41", snapshot.url.encodedPath)
		assertEquals(
			listOf(QueryEntry("tag", "a"), QueryEntry("tag", "b"), QueryEntry("flag", null), QueryEntry("empty", ""), QueryEntry("plus", "a+b")),
			snapshot.url.query
		)
		assertEquals(emptyList(), RequestSnapshots.fromUrl("GET", "https://example.test/x?").url.query)
		assertFailsWith<InvalidRequestException> { RequestSnapshots.fromUrl("GET", "https://example.test/x?x=%FF") }
		assertFailsWith<InvalidRequestException> { RequestSnapshots.fromUrl("GET", "https://example.test/x?x=%GG") }
		assertFailsWith<InvalidRequestException> { RequestSnapshots.fromUrl("GET", "https://example.test/%C3%28") }
		assertFailsWith<InvalidRequestException> {
			RequestSnapshots.fromUrl("GET", "https://example.test/x", listOf(HeaderEntry("Bad Name", "value")))
		}
		assertFailsWith<InvalidRequestException> {
			RequestSnapshots.fromUrl("GET", "https://example.test/x", listOf(HeaderEntry("X-Test", "one\r\ntwo")))
		}
		assertFailsWith<IllegalArgumentException> { ExactPath("/x%GG") }
	}

	@Test
	fun absentEmptyAndUnavailableBodiesAreDistinct() {
		val engine = RuleEngine().apply { replaceRules(mockRules {
			rule("empty", "/x") {
				match { bodyExactBytes(byteArrayOf()) }
				respond { bodyText("empty") }
			}
		}) }
		fun decide(body: RequestBodySnapshot) = engine.decide(
			RequestSnapshots.fromUrl("POST", "https://example.test/x", body = body)
		)
		assertIs<MockFailure.NoMatchingRule>(assertIs<Decision.Fail>(decide(RequestBodySnapshot.Absent)).error)
		assertEquals("empty", text(decide(RequestBodySnapshot.Buffered(byteArrayOf()))))
		assertIs<MockFailure.NoMatchingRule>(assertIs<Decision.Fail>(decide(RequestBodySnapshot.Unavailable(BodyUnavailableReason.ONE_SHOT))).error)
	}

	@Test
	fun normalizationDoesNotDependOnDefaultLocale() {
		val old = Locale.getDefault()
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"))
			assertEquals("INVITE", RequestSnapshots.fromUrl("invite", "https://IDENTITY.test/x").method)
			assertEquals("identity.test", request().let { RequestSnapshots.fromUrl("GET", "https://IDENTITY.test/x").url.host })
		} finally {
			Locale.setDefault(old)
		}
	}

	@Test
	fun bodyAndResponseArraysAreOwned() {
		val input = byteArrayOf(1)
		val body = RequestBodySnapshot.Buffered(input)
		input[0] = 2
		body.bytes[0] = 3
		assertEquals(1, body.bytes[0])
		val response = ResponseBody.Bytes(input)
		input[0] = 4
		response.bytes[0] = 5
		assertEquals(2, response.bytes[0])
	}

	@Test
	fun priorityAndRegistrationOrderSurviveUpsert() {
		val engine = RuleEngine()
		fun rule(id: String, priority: Int) = Rule(
			RuleId(id), ExactPath("/x"), priority,
			responder = StaticResponder(ResponseSpec(body = ResponseBody.Bytes(id.toByteArray())))
		)
		engine.replaceRules(listOf(rule("a", 0), rule("b", 10), rule("c", 0)))
		assertEquals("b", text(engine.decide(request())))
		engine.upsertRule(rule("b", 0))
		assertEquals("a", text(engine.decide(request())))
		engine.removeRule(RuleId("a"))
		assertEquals("b", text(engine.decide(request())))
	}

	@Test
	fun roundRobinAndRejectedPublicationPreserveState() {
		val engine = RuleEngine()
		val rule = Rule(
			RuleId("x"), ExactPath("/x"), responder = RoundRobinResponder(
				listOf(
					ResponseSpec(body = ResponseBody.Bytes("one".toByteArray())),
					ResponseSpec(body = ResponseBody.Bytes("two".toByteArray())),
				)
			)
		)
		engine.replaceRules(listOf(rule))
		assertEquals("one", text(engine.decide(request())))
		assertFailsWith<ConfigurationException> { engine.replaceRules(listOf(rule, rule)) }
		assertEquals("two", text(engine.decide(request())))
		engine.resetScenarios()
		assertEquals("one", text(engine.decide(request())))
		assertIs<MockFailure.NoMatchingRule>(assertIs<Decision.Fail>(engine.decide(request("/other"))).error)
	}

	@Test
	fun concurrentRoundRobinUsesEverySlotExactlyOnce() {
		val count = 64
		val engine = RuleEngine()
		engine.replaceRules(
			listOf(
				Rule(
					RuleId("slots"), ExactPath("/x"), responder = RoundRobinResponder(
			(0 until count).map { ResponseSpec(body = ResponseBody.Bytes(it.toString().toByteArray())) }
		))))
		val pool = Executors.newFixedThreadPool(8)
		val start = CountDownLatch(1)
		try {
			val futures = (0 until count).map { pool.submit<String> { start.await(); text(engine.decide(request())) } }
			start.countDown()
			assertEquals((0 until count).map(Int::toString).toSet(), futures.map { it.get(10, TimeUnit.SECONDS) }.toSet())
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun dslReadsAssetsBeforePublicationAndMatchesBuiltIns() {
		var reads = 0
		val rules = mockRules({ reads++; "asset".toByteArray() }) {
			rule("read", "/x") {
				match { method("GET"); query { containsValue("flag", null) } }
				respond { bodyAsset("reply.txt") }
			}
		}
		val engine = RuleEngine().apply { replaceRules(rules) }
		assertEquals("asset", text(engine.decide(request("/x?flag"))))
		assertEquals(1, reads)
		assertIs<Decision.Fail>(engine.decide(request("/x?flag=")))
		assertFailsWith<ConfigurationException> {
			mockRules({ error("should not read") }) { rule("bad", "/x") { respond { bodyAsset("../secret") } } }
		}
		assertEquals("asset", text(engine.decide(request("/x?flag"))))
	}

	@Test
	fun callbackFailureStopsSearchAndPathIsMandatory() {
		val engine = RuleEngine()
		engine.replaceRules(
			listOf(
				Rule(RuleId("bad"), ExactPath("/x"), priority = 1, matcher = { error("boom") }, responder = StaticResponder(ResponseSpec())),
				Rule(RuleId("good"), ExactPath("/x"), responder = StaticResponder(ResponseSpec())),
			)
		)
		assertIs<MockFailure.CallbackFailure>(assertIs<Decision.Fail>(engine.decide(request())).error)
		assertIs<MockFailure.NoMatchingRule>(assertIs<Decision.Fail>(engine.decide(request("/y"))).error)
	}

	@Test
	fun oldDecisionKeepsCapturedRuleAndCounterGeneration() {
		val engine = RuleEngine()
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val old = Rule(RuleId("x"), ExactPath("/x"), matcher = RequestMatcher {
			entered.countDown()
			release.await(5, TimeUnit.SECONDS)
		}, responder = RoundRobinResponder(listOf(
			ResponseSpec(body = ResponseBody.Bytes("old-0".toByteArray())),
			ResponseSpec(body = ResponseBody.Bytes("old-1".toByteArray())),
		)))
		engine.replaceRules(listOf(old))
		val pool = Executors.newSingleThreadExecutor()
		try {
			val pending = pool.submit<String> { text(engine.decide(request())) }
			kotlin.test.assertTrue(entered.await(5, TimeUnit.SECONDS))
			engine.replaceRules(listOf(Rule(RuleId("x"), ExactPath("/x"), responder =
				StaticResponder(ResponseSpec(body = ResponseBody.Bytes("new".toByteArray()))))))
			release.countDown()
			assertEquals("old-0", pending.get(5, TimeUnit.SECONDS))
			assertEquals("new", text(engine.decide(request())))
		} finally { release.countDown(); pool.shutdownNow() }
	}

	@Test
	fun sharedScenarioAdvancesAcrossRulesAndUnrelatedUpsertKeepsCounter() {
		val shared = ScenarioKey.Shared("checkout")
		fun sequence(id: String, path: String) = Rule(RuleId(id), ExactPath(path), responder =
			RoundRobinResponder(listOf(
				ResponseSpec(body = ResponseBody.Bytes("$id-0".toByteArray())),
				ResponseSpec(body = ResponseBody.Bytes("$id-1".toByteArray())),
			), shared))
		val engine = RuleEngine().apply { replaceRules(listOf(sequence("a", "/a"), sequence("b", "/b"))) }
		assertEquals("a-0", text(engine.decide(request("/a"))))
		assertEquals("b-1", text(engine.decide(request("/b"))))
		engine.upsertRule(Rule(RuleId("other"), ExactPath("/other"), responder = StaticResponder(ResponseSpec())))
		assertEquals("a-0", text(engine.decide(request("/a"))))
		engine.upsertRule(sequence("b", "/b"))
		assertEquals("a-0", text(engine.decide(request("/a"))))
		assertEquals("a-1", text(engine.decide(request("/a"))))
		engine.removeRule(RuleId("b"))
		assertEquals("a-0", text(engine.decide(request("/a"))))
	}

	@Test
	fun invalidStaticResponseIsRejectedBeforePublication() {
		val engine = RuleEngine()
		val good = Rule(RuleId("good"), ExactPath("/x"), responder = StaticResponder(ResponseSpec()))
		engine.replaceRules(listOf(good))
		val bad = Rule(RuleId("bad"), ExactPath("/x"), responder = StaticResponder(ResponseSpec(
			status = 204, body = ResponseBody.Bytes(byteArrayOf(1)))))
		assertFailsWith<ConfigurationException> { engine.replaceRules(listOf(bad)) }
		assertEquals(RuleId("good"), assertIs<Decision.Mock>(engine.decide(request())).ruleId)
		val dynamic = Rule(RuleId("dynamic"), ExactPath("/x"), responder = DynamicResponder { _, _ ->
			ResponseSpec(status = 204, body = ResponseBody.Bytes(byteArrayOf(1)))
		})
		engine.replaceRules(listOf(dynamic))
		assertIs<MockFailure.InvalidResponse>(assertIs<Decision.Fail>(engine.decide(request())).error)
	}
}
