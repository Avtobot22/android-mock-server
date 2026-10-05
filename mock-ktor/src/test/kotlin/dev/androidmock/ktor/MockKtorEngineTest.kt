package dev.androidmock.ktor

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.MockFailure
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.QueryEntry
import dev.androidmock.core.response.ResponseBody
import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.rule.DynamicResponder
import dev.androidmock.core.rule.ExactPath
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.RuleId
import dev.androidmock.core.rule.StaticResponder
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MockKtorEngineTest {

	@Test
	fun mockMissAndClientPluginsUseOnlyMockEngine() = runBlocking {
		val engine = RuleEngine().apply {
			replaceRules(
				listOf(
					Rule(
						RuleId("x"), ExactPath("/x"),
						responder = StaticResponder(
							ResponseSpec(
								201,
								listOf(HeaderEntry("X-Mock", "yes")), ResponseBody.Bytes("hello".toByteArray())
							)
						)
					)
				)
			)
		}
		val requests = AtomicInteger()
		val responses = AtomicInteger()
		val observer = createClientPlugin("ObserveMock") {
			onRequest { _, _ -> requests.incrementAndGet() }
			onResponse { responses.incrementAndGet() }
		}
		val client = HttpClient(mockKtorEngine(engine)) { install(observer) }
		client.use { client ->
			val response = client.get("https://example.test/x")
			assertEquals(201, response.status.value)
			assertEquals("yes", response.headers["X-Mock"])
			assertEquals("hello", response.bodyAsText())
			val failure = assertFailsWith<KtorMockException> { client.get("https://example.test/missing") }
			assertIs<MockFailure.NoMatchingRule>(failure.failure)
			assertEquals(2, requests.get())
			assertEquals(1, responses.get())
		}
	}

	@Test
	fun delayIsCancelledWithCall() = runBlocking {
		val entered = CompletableDeferred<Unit>()
		val engine = RuleEngine().apply {
			replaceRules(
				listOf(
					Rule(
						RuleId("x"), ExactPath("/x"),
						responder = DynamicResponder { _, _ ->
							entered.complete(Unit)
							ResponseSpec(body = ResponseBody.Bytes("late".toByteArray()), delay = 10.seconds)
						})
				)
			)
		}
		val client = HttpClient(mockKtorEngine(engine))
		client.use { client ->
			val pending = async { client.get("https://example.test/x") }
			entered.await()
			pending.cancelAndJoin()
			assertTrue(pending.isCancelled)
		}
	}

	@Test
	fun queryFlagsAndRepeatedValuesReachCoreWithoutCollapse(): Unit = runBlocking {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("query", "/x") {
					match { query { exactList(QueryEntry("tag", "a"), QueryEntry("tag", "b"), QueryEntry("flag", null)) } }
					respond { bodyText("matched") }
				}
			})
		}
		val client = HttpClient(mockKtorEngine(engine))
		client.use { client ->
			assertEquals("matched", client.get("https://example.test/x?tag=a&tag=b&flag").bodyAsText())
			val failure = assertFailsWith<KtorMockException> {
				client.get("https://example.test/x?tag=a&tag=b&flag=")
			}
			assertIs<MockFailure.NoMatchingRule>(failure.failure)
		}
	}

	@Test
	fun byteArrayRequestBodyMatchesWithoutTransport() = runBlocking {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("post", "/x") {
					match { method("POST"); bodyExactUtf8("abc") }
					respond { status(202); bodyText("accepted") }
				}
			})
		}
		val client = HttpClient(mockKtorEngine(engine))
		client.use { client ->
			val response = client.post("https://example.test/x") { setBody("abc".toByteArray()) }
			assertEquals(202, response.status.value)
			assertEquals("accepted", response.bodyAsText())
		}
	}

	@Test
	fun headDeliversStatusAndHeadersWithoutBody() = runBlocking {
		val engine = RuleEngine().apply {
			replaceRules(
				listOf(
					Rule(
						RuleId("head"), ExactPath("/x"),
						responder = StaticResponder(
							ResponseSpec(
								201,
								listOf(HeaderEntry("X-Mock", "yes")), ResponseBody.Bytes("hidden".toByteArray())
							)
						)
					)
				)
			)
		}
		val client = HttpClient(mockKtorEngine(engine))
		client.use { client ->
			val response = client.head("https://example.test/x")
			assertEquals(201, response.status.value)
			assertEquals("yes", response.headers["X-Mock"])
			assertEquals("", response.bodyAsText())
		}
	}
}
