package dev.androidmock.okhttp.server

import dev.androidmock.core.dsl.BodyAssetResolver
import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.BodyUnavailableReason
import dev.androidmock.core.request.QueryEntry
import dev.androidmock.core.request.RequestBodySnapshot
import dev.androidmock.core.request.RequestSnapshot
import dev.androidmock.core.response.ResponseSpec
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopbackMappingTest {
    @Test
    fun getDeliversStatusRepeatedHeadersAndExactBytes() {
        val bytes = byteArrayOf(0, 1, -1, 10)
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("health", "/health") {
                    match { method("GET") }
                    respond {
                        status(201)
                        header("X-Item", "one")
                        header("X-Item", "two")
                        bodyBytes(bytes)
                    }
                }
            })
        }
        withServer(engine) { server, client ->
            client.get(server.baseUrl, "health").use {
                assertEquals(201, it.code)
                assertEquals(listOf("one", "two"), it.headers.values("X-Item"))
                assertContentEquals(bytes, it.body!!.bytes())
            }
        }
    }

    @Test
    fun logicalOriginPreservesEncodedPathOrderedQueryAndPhysicalHostHeader() {
        val observed = AtomicReference<RequestSnapshot>()
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("encoded", "/items/a%2Fb") {
                    match {
                        method("GET")
                        scheme("https")
                        host("api.example.test")
                        port(8443)
                        query {
                            exactList(
                                QueryEntry("tag", "a+b"), QueryEntry("tag", "a b"),
                                QueryEntry("flag", null), QueryEntry("empty", ""),
                            )
                        }
                        headers { contains("x-repeat", "one"); contains("X-REPEAT", "two") }
                    }
                    respondWith { request, _ ->
                        observed.set(request)
                        ResponseSpec()
                    }
                }
            })
        }
        withServer(engine, "https://api.example.test:8443/") { server, client ->
            val request = Request.Builder()
                .url(server.baseUrl.resolve("items/a%2Fb?tag=a+b&tag=a%20b&flag&empty=")!!)
                .addHeader("X-Repeat", "one").addHeader("x-repeat", "two").build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertEquals("/items/a%2Fb", observed.get().url.encodedPath)
            assertEquals(listOf("127.0.0.1:${server.baseUrl.port}"), observed.get().headers.values("Host"))
            assertEquals(listOf("one", "two"), observed.get().headers.values("X-Repeat"))
        }
    }

    @Test
    fun postJsonBodyMatchesActualReceivedBytesAndBodyJsonKeepsContentType() {
        val json = """{"name":"Ada"}"""
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("post", "/users") {
                    match { method("POST"); bodyExactUtf8(json) }
                    respond {
                        bodyJson(mapOf("name" to "Ada"), MapSerializer(String.serializer(), String.serializer()))
                    }
                }
            })
        }
        withServer(engine) { server, client ->
            val request = Request.Builder().url(server.baseUrl.resolve("users")!!)
                .post(json.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use {
                assertEquals(200, it.code)
                assertEquals("application/json; charset=utf-8", it.header("Content-Type"))
                assertEquals(json, it.body!!.string())
            }
        }
    }

    @Test
    fun bodyAssetIsLoadedOnceBeforeRequestsAndDeliveredUnchanged() {
        val reads = AtomicInteger()
        val bytes = byteArrayOf(9, 0, -120)
        val rules = mockRules(bodyAssets = BodyAssetResolver { path ->
            assertEquals("response.bin", path)
            reads.incrementAndGet()
            bytes
        }) {
            rule("asset", "/asset") { respond { bodyAsset("response.bin") } }
        }
        assertEquals(1, reads.get())
        withServer(RuleEngine().apply { replaceRules(rules) }) { server, client ->
            repeat(2) {
                client.get(server.baseUrl, "asset").use { assertContentEquals(bytes, it.body!!.bytes()) }
            }
        }
        assertEquals(1, reads.get())
    }

    @Test
    fun emptyReceivedBodyIsAbsentAndPutPatchBodiesAreBuffered() {
        val snapshots = mutableListOf<RequestSnapshot>()
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("capture", "/body") {
                    respondWith { request, _ -> snapshots += request; ResponseSpec() }
                }
            })
        }
        withServer(engine) { server, client ->
            for ((method, body) in listOf("POST" to "", "PUT" to "put", "PATCH" to "patch")) {
                client.newCall(Request.Builder().url(server.baseUrl.resolve("body")!!)
                    .method(method, body.toRequestBody()).build()).execute().close()
            }
            assertIs<RequestBodySnapshot.Absent>(snapshots[0].body)
            assertEquals("PUT", snapshots[1].method)
            assertContentEquals("put".toByteArray(), assertIs<RequestBodySnapshot.Buffered>(snapshots[1].body).bytes)
            assertEquals("PATCH", snapshots[2].method)
            assertContentEquals("patch".toByteArray(), assertIs<RequestBodySnapshot.Buffered>(snapshots[2].body).bytes)
        }
    }

    @Test
    fun knownAndChunkedOversizeBodiesAreUnavailableAndCannotMatchBodyConditions() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("must-not-match-truncated-prefix", "/body") {
                    match { bodyExactUtf8("abcd") }
                    respond { bodyText("incorrect") }
                }
                rule("large", "/body") {
                    matching { it.body == RequestBodySnapshot.Unavailable(BodyUnavailableReason.TOO_LARGE) }
                    respond { bodyText("unavailable") }
                }
            })
        }
        withServer(engine, maxRequestBytes = 4) { server, client ->
            val chunked = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun writeTo(sink: BufferedSink) { sink.writeUtf8("abcdefgh") }
            }
            for (body in listOf("abcdefgh".toRequestBody(), chunked)) {
                client.newCall(Request.Builder().url(server.baseUrl.resolve("body")!!).post(body).build())
                    .execute().use {
                        assertEquals(200, it.code)
                        assertEquals("unavailable", it.body!!.string())
                    }
            }
        }
    }

    @Test
    fun bodyExactlyAtCaptureLimitRemainsBuffered() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("exact", "/body") {
                    match { method("POST"); bodyExactUtf8("abcd") }
                    respond { bodyText("matched") }
                }
            })
        }
        withServer(engine, maxRequestBytes = 4) { server, client ->
            client.newCall(Request.Builder().url(server.baseUrl.resolve("body")!!)
                .post("abcd".toRequestBody()).build()).execute().use {
                assertEquals("matched", it.body!!.string())
            }
        }
    }

    @Test
    fun headAndNoContentStatusesDeliverNoBodyAndLeaveConnectionUsable() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("head", "/head") { respond { bodyText("head must omit this") } }
                rule("empty", "/empty") { respond { status(204) } }
                rule("unchanged", "/unchanged") { respond { status(304) } }
                rule("next", "/next") { respond { bodyText("next response") } }
            })
        }
        withServer(engine) { server, client ->
            val head = Request.Builder().url(server.baseUrl.resolve("head")!!).head().build()
            client.newCall(head).execute().use {
                assertEquals(200, it.code)
                assertEquals("head must omit this".toByteArray().size.toString(), it.header("Content-Length"))
                assertEquals(0, it.body!!.bytes().size)
            }
            for ((path, status) in listOf("empty" to 204, "unchanged" to 304)) {
                client.get(server.baseUrl, path).use {
                    assertEquals(status, it.code)
                    assertNull(it.header("Content-Length"))
                    assertNull(it.header("Transfer-Encoding"))
                    assertEquals(0, it.body!!.bytes().size)
                }
            }
            client.get(server.baseUrl, "next").use { assertEquals("next response", it.body!!.string()) }
        }
    }

    @Test
    fun conflictingLengthAndChunkingHeadersCannotCorruptDeliveredBody() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("framing", "/framing") {
                    respond {
                        header("Content-Length", "9999")
                        header("Transfer-Encoding", "chunked")
                        bodyText("actual")
                    }
                }
            })
        }
        withServer(engine) { server, client ->
            repeat(2) {
                client.get(server.baseUrl, "framing").use {
                    assertEquals(200, it.code)
                    assertEquals("actual", it.body!!.string())
                    assertTrue(it.header("Transfer-Encoding") == null || it.header("Content-Length") == null)
                }
            }
        }
    }
}
