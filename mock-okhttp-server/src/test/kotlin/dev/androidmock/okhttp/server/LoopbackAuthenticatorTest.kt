package dev.androidmock.okhttp.server

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.response.ResponseBody as CoreResponseBody
import dev.androidmock.core.response.ResponseSpec
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoopbackAuthenticatorTest {
    @Test
    fun actualOkHttpAuthenticatorReceives401AndCreatesAuthorizedFollowUp() {
        val attempts = CopyOnWriteArrayList<String?>()
        val authentications = AtomicInteger()
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("private", "/private") {
                    respondWith { request, _ ->
                        val authorization = request.headers.values("Authorization").singleOrNull()
                        attempts += authorization
                        if (authorization == "Bearer A2") {
                            ResponseSpec(body = CoreResponseBody.Bytes("authorized".toByteArray()))
                        } else ResponseSpec(status = 401)
                    }
                }
            })
        }
        withServer(engine) { server, _ ->
            val client = testClient {
                authenticator { _, response ->
                    authentications.incrementAndGet()
                    assertEquals(401, response.code)
                    assertNull(response.priorResponse)
                    response.request.newBuilder().header("Authorization", "Bearer A2").build()
                }
            }
            try {
                client.newCall(Request.Builder().url(server.baseUrl.resolve("private")!!)
                    .header("Authorization", "Bearer A1").build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals(401, it.priorResponse!!.code)
                    assertEquals("authorized", it.body!!.string())
                }
                assertEquals(1, authentications.get())
                assertEquals(listOf<String?>("Bearer A1", "Bearer A2"), attempts.toList())
            } finally { client.closeTestClient() }
        }
    }

    @Test
    fun authenticatorCanStopAfterRepeated401WithoutInfiniteRetries() {
        val requests = AtomicInteger()
        val authentications = AtomicInteger()
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("reject", "/private") {
                    respondWith { _, _ -> requests.incrementAndGet(); ResponseSpec(status = 401) }
                }
            })
        }
        withServer(engine) { server, _ ->
            val client = testClient {
                authenticator { _, response ->
                    authentications.incrementAndGet()
                    if (response.priorResponse != null) null
                    else response.request.newBuilder().header("Authorization", "Bearer A2").build()
                }
            }
            try {
                client.get(server.baseUrl, "private").use { assertEquals(401, it.code) }
                assertEquals(2, requests.get())
                assertEquals(2, authentications.get())
            } finally { client.closeTestClient() }
        }
    }

    @Test
    fun retrofitMainAndIsolatedRefreshClientsShareServerAndRuleEngine() {
        val calls = CopyOnWriteArrayList<String>()
        val token = AtomicReference("A1")
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("private", "/private") {
                    respondWith { request, _ ->
                        val authorization = request.headers.values("Authorization").single()
                        calls += "GET $authorization"
                        if (authorization == "Bearer A2") ResponseSpec(body = CoreResponseBody.Bytes("private payload".toByteArray()))
                        else ResponseSpec(status = 401)
                    }
                }
                rule("refresh", "/refresh") {
                    match { method("POST"); bodyExactUtf8("refresh-fixture") }
                    respondWith { request, _ ->
                        assertEquals(emptyList(), request.headers.values("Authorization"))
                        calls += "POST refresh"
                        ResponseSpec(body = CoreResponseBody.Bytes("A2".toByteArray()))
                    }
                }
            })
        }
        withServer(engine) { server, _ ->
            val refreshClient = testClient { authenticator(Authenticator.NONE) }
            val refreshApi = Retrofit.Builder().baseUrl(server.baseUrl).client(refreshClient).build()
                .create(RefreshApi::class.java)
            val mainClient = testClient {
                addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${token.get()}").build())
                }
                authenticator { _, response ->
                    if (response.priorResponse != null) null else {
                        val refreshed = refreshApi.refresh("refresh-fixture".toRequestBody()).execute()
                        val replacement = refreshed.body()!!.use { it.string() }
                        token.set(replacement)
                        response.request.newBuilder().header("Authorization", "Bearer $replacement").build()
                    }
                }
            }
            try {
                val mainApi = Retrofit.Builder().baseUrl(server.baseUrl).client(mainClient).build().create(PrivateApi::class.java)
                val response = mainApi.privateResource().execute()
                assertEquals(200, response.code())
                assertEquals("private payload", response.body()!!.use { it.string() })
                assertEquals(listOf("GET Bearer A1", "POST refresh", "GET Bearer A2"), calls)
                assertEquals("A2", token.get())
            } finally {
                mainClient.closeTestClient()
                refreshClient.closeTestClient()
            }
        }
    }

    private interface PrivateApi { @GET("private") fun privateResource(): Call<ResponseBody> }
    private interface RefreshApi { @POST("refresh") fun refresh(@Body body: RequestBody): Call<ResponseBody> }
}
