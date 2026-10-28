package dev.androidmock.okhttp.server

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.response.ResponseSpec
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Filter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class LoopbackFailureTest {
    @Test
    fun unknownRouteIsExplicitFailureWithoutUpstreamOrSensitiveRequestDetails() {
        withServer(RuleEngine(), "https://unreachable.example.test/") { server, client ->
            val request = Request.Builder().url(server.baseUrl.resolve("unknown?token=query-secret")!!)
                .header("Authorization", "Bearer header-secret").build()
            client.newCall(request).execute().use {
                assertEquals(599, it.code)
                assertEquals("NoMatchingRule", it.header("X-Android-Mock-Failure"))
                assertEquals("NoMatchingRule", MockServerFailureProtocol.failureName(it))
                val diagnostic = it.body!!.string()
                assertFalse(diagnostic.contains("query-secret"))
                assertFalse(diagnostic.contains("header-secret"))
                assertFalse(diagnostic.contains("unknown"))
            }
        }
    }

    @Test
    fun unknownHeadHasNoDiagnosticBodyAndFollowingGetRemainsReadable() {
        withServer(RuleEngine()) { server, client ->
            client.newCall(Request.Builder().url(server.baseUrl.resolve("missing")!!).head().build()).execute().use {
                assertEquals("NoMatchingRule", MockServerFailureProtocol.failureName(it))
                assertEquals(0, it.body!!.bytes().size)
            }
            client.get(server.baseUrl, "missing").use {
                assertEquals("NoMatchingRule", MockServerFailureProtocol.failureName(it))
                assertTrue(it.body!!.string().isNotBlank())
            }
        }
    }

    @Test
    fun verboseTransportLoggingDoesNotExposeQueryCredentialsOrCallbackSecrets() {
        val logger = Logger.getLogger("okhttp3.mockwebserver.MockWebServer")
        val previousLevel = logger.level
        val previousFilter = logger.filter
        val messages = CopyOnWriteArrayList<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                messages += record.message.orEmpty() + record.parameters.orEmpty().joinToString()
            }
            override fun flush() = Unit
            override fun close() = Unit
        }.apply { level = Level.ALL }
        val existingFilter = Filter { true }
        logger.level = Level.ALL
        logger.filter = existingFilter
        logger.addHandler(handler)
        try {
            val engine = RuleEngine().apply {
                replaceRules(mockRules {
                    rule("secret", "/secret") {
                        respondWith { _, _ -> throw IllegalStateException("exception-secret") }
                    }
                })
            }
            withServer(engine) { server, client ->
                client.newCall(Request.Builder().url(server.baseUrl.resolve("secret?access=query-secret")!!)
                    .header("Authorization", "Bearer header-secret").build()).execute().use {
                    assertEquals("CallbackFailure", MockServerFailureProtocol.failureName(it))
                }
                assertFalse(messages.any { message ->
                    listOf("query-secret", "header-secret", "exception-secret").any(message::contains)
                })
            }
            assertSame(existingFilter, logger.filter)
        } finally {
            logger.removeHandler(handler)
            logger.filter = previousFilter
            logger.level = previousLevel
        }
    }

    @Test
    fun invalidEncodedUtf8RequestIsInvalidRequestFailure() {
        withServer(RuleEngine()) { server, client ->
            client.get(server.baseUrl, "invalid%FF").use {
                assertEquals(599, it.code)
                assertEquals("InvalidRequest", MockServerFailureProtocol.failureName(it))
            }
        }
    }

    @Test
    fun callbackFailureDoesNotExposeExceptionStackOrSecrets() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("callback", "/callback") {
                    respondWith { _, _ -> throw IllegalStateException("private-token-secret") }
                }
            })
        }
        withServer(engine) { server, client ->
            client.get(server.baseUrl, "callback").use {
                assertEquals(599, it.code)
                assertEquals("CallbackFailure", MockServerFailureProtocol.failureName(it))
                val diagnostic = it.body!!.string()
                assertFalse(diagnostic.contains("private-token-secret"))
                assertFalse(diagnostic.contains("IllegalStateException"))
                assertFalse(diagnostic.contains("LoopbackFailureTest"))
            }
        }
    }

    @Test
    fun invalidDynamicResponseIsDiagnosticFailureInsteadOfBackendResponse() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("invalid", "/invalid") { respondWith { _, _ -> ResponseSpec(status = 999) } }
            })
        }
        withServer(engine) { server, client ->
            client.get(server.baseUrl, "invalid").use {
                assertEquals(599, it.code)
                assertEquals("InvalidResponse", MockServerFailureProtocol.failureName(it))
            }
        }
    }

    @Test
    fun realBackendErrorStatusesRemainOrdinaryHttpResponses() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                for (responseStatus in listOf(401, 403, 500, 599)) {
                    rule("status-$responseStatus", "/status/$responseStatus") { respond { status(responseStatus); bodyText("backend") } }
                }
            })
        }
        withServer(engine) { server, client ->
            for (status in listOf(401, 403, 500, 599)) {
                client.get(server.baseUrl, "status/$status").use {
                    assertEquals(status, it.code)
                    assertNull(MockServerFailureProtocol.failureName(it))
                    assertEquals("backend", it.body!!.string())
                }
            }
        }
    }

    @Test
    fun diagnosticRecognizerRequiresReservedStatusAndKnownHeaderValue() {
        fun response(status: Int, marker: String?) = Response.Builder()
            .request(Request.Builder().url("http://127.0.0.1/").build())
            .protocol(Protocol.HTTP_1_1).message("test").code(status)
            .apply { if (marker != null) header(MockServerFailureProtocol.HEADER_NAME, marker) }
            .build()
        assertEquals(599, MockServerFailureProtocol.STATUS_CODE)
        for (failure in listOf("NoMatchingRule", "InvalidRequest", "CallbackFailure", "InvalidResponse")) {
            assertEquals(failure, MockServerFailureProtocol.failureName(response(599, failure)))
            assertNull(MockServerFailureProtocol.failureName(response(500, failure)))
        }
        assertNull(MockServerFailureProtocol.failureName(response(599, null)))
        assertNull(MockServerFailureProtocol.failureName(response(599, "UnrecognizedFailure")))
        val duplicated = response(599, "NoMatchingRule").newBuilder()
            .addHeader(MockServerFailureProtocol.HEADER_NAME, "NoMatchingRule").build()
        assertNull(MockServerFailureProtocol.failureName(duplicated))
    }
}
