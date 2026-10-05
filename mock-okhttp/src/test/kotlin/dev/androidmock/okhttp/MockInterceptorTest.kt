package dev.androidmock.okhttp

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.MockFailure
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.QueryEntry
import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.rule.ExactPath
import dev.androidmock.core.rule.RequestMatcher
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.RuleId
import dev.androidmock.core.rule.StaticResponder
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MockInterceptorTest {

	private fun rule(body: String, delay: kotlin.time.Duration = kotlin.time.Duration.ZERO) =
		Rule(
			RuleId("x"), ExactPath("/x"), responder = StaticResponder(
				ResponseSpec(
					status = 201,
					headers = listOf(HeaderEntry("X-Mock", "one"), HeaderEntry("X-Mock", "two")),
					body = dev.androidmock.core.response.ResponseBody.Bytes(body.toByteArray()), delay = delay
				)
			)
		)

	@Test
	fun mockAndMissNeverReachNetworkAndRetrofitUsesConfiguredClient() {
		val network = AtomicInteger()
		val engine = RuleEngine().apply { replaceRules(listOf(rule("from mock"))) }
		val client = OkHttpClient.Builder().addInterceptor(MockInterceptor(engine))
			.addNetworkInterceptor { chain -> network.incrementAndGet(); chain.proceed(chain.request()) }.build()
		val response = client.newCall(Request.Builder().url("https://example.test/x").build()).execute()
		response.use {
			assertEquals(201, it.code)
			assertEquals(listOf("one", "two"), it.headers.values("X-Mock"))
			assertEquals("from mock", it.body!!.string())
		}
		val error = assertFailsWith<MockIOException> {
			client.newCall(Request.Builder().url("https://example.test/missing").build()).execute()
		}
		assertIs<MockFailure.NoMatchingRule>(error.failure)
		assertEquals(0, network.get())

		val retrofit = Retrofit.Builder().baseUrl("https://example.test/").client(client).build()
		val api = retrofit.create(Api::class.java)
		val retrofitResponse = api.get().execute()
		assertEquals(201, retrofitResponse.code())
		assertEquals("from mock", retrofitResponse.body()!!.string())
		assertEquals(0, network.get())
	}

	@Test
	fun cancellationStopsDelay() {
		val entered = CountDownLatch(1)
		val delayed = Rule(RuleId("x"), ExactPath("/x"), matcher = RequestMatcher {
			entered.countDown()
			true
		}, responder = StaticResponder(ResponseSpec(delay = 5.seconds)))
		val engine = RuleEngine().apply { replaceRules(listOf(delayed)) }
		val client = OkHttpClient.Builder().addInterceptor(MockInterceptor(engine)).build()
		val call = client.newCall(Request.Builder().url("https://example.test/x").build())
		val finished = CountDownLatch(1)
		var failure: Throwable? = null
		val thread = Thread {
			try {
				call.execute().close()
			} catch (e: Throwable) {
				failure = e
			} finally {
				finished.countDown()
			}
		}
		thread.start()
		assertTrue(entered.await(1, TimeUnit.SECONDS))
		call.cancel()
		assertTrue(finished.await(2, TimeUnit.SECONDS))
		assertIs<InterruptedIOException>(failure)
	}

	@Test
	fun headDeliversNoBody() {
		val engine = RuleEngine().apply { replaceRules(listOf(rule("secret"))) }
		val client = OkHttpClient.Builder().addInterceptor(MockInterceptor(engine)).build()
		client.newCall(Request.Builder().url("https://example.test/x").head().build()).execute().use {
			assertEquals(201, it.code)
			assertEquals(0, it.body!!.bytes().size)
		}
	}

	@Test
	fun queryFlagsAndRepeatedValuesReachCoreWithoutCollapse() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("query", "/x") {
					match { query { exactList(QueryEntry("tag", "a"), QueryEntry("tag", "b"), QueryEntry("flag", null)) } }
					respond { bodyText("matched") }
				}
			})
		}
		val client = OkHttpClient.Builder().addInterceptor(MockInterceptor(engine)).build()
		client.newCall(Request.Builder().url("https://example.test/x?tag=a&tag=b&flag").build()).execute().use {
			assertEquals("matched", it.body!!.string())
		}
		val error = assertFailsWith<MockIOException> {
			client.newCall(Request.Builder().url("https://example.test/x?tag=a&tag=b&flag=").build()).execute()
		}
		assertIs<MockFailure.NoMatchingRule>(error.failure)
	}

	@Test
	fun repeatableBodyRequiresOptInAndOneShotIsNeverConsumed() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("body", "/x") {
					match { method("POST"); bodyExactUtf8("abc") }
					respond { bodyText("matched") }
				}
			})
		}
		val client = OkHttpClient.Builder()
			.addInterceptor(MockInterceptor(engine, RequestBodyCapture.REPEATABLE)).build()
		client.newCall(
			Request.Builder().url("https://example.test/x")
				.post("abc".toRequestBody()).build()
		).execute().use {
			assertEquals("matched", it.body!!.string())
		}
		val writes = AtomicInteger()
		val oneShot = object : RequestBody() {
			override fun contentType(): MediaType? = null
			override fun contentLength(): Long = 3
			override fun isOneShot(): Boolean = true
			override fun writeTo(sink: BufferedSink) {
				writes.incrementAndGet(); sink.writeUtf8("abc")
			}
		}
		val failure = assertFailsWith<MockIOException> {
			client.newCall(Request.Builder().url("https://example.test/x").post(oneShot).build()).execute()
		}
		assertIs<MockFailure.NoMatchingRule>(failure.failure)
		assertEquals(0, writes.get())
	}

	private interface Api {

		@GET("x")
		fun get(): Call<ResponseBody>
	}
}
