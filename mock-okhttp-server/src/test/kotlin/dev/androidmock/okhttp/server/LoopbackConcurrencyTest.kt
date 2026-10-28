package dev.androidmock.okhttp.server

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LoopbackConcurrencyTest {
    @Test
    fun tenConcurrentRequestsConsumeOneSharedScenarioWithoutDuplicateSlots() {
        val engine = scenarioEngine()
        withServer(engine) { server, client ->
            val executor = Executors.newFixedThreadPool(10)
            val begin = CountDownLatch(1)
            try {
                val requests = (0 until 10).map {
                    executor.submit<String> {
                        check(begin.await(5, TimeUnit.SECONDS))
                        client.get(server.baseUrl, "sequence").use { response -> response.body!!.string() }
                    }
                }
                begin.countDown()
                val bodies = requests.map { it.get(5, TimeUnit.SECONDS) }
                assertEquals((0 until 10).map(Int::toString).toSet(), bodies.toSet())
                engine.resetScenarios()
                client.get(server.baseUrl, "sequence").use { assertEquals("0", it.body!!.string()) }
            } finally {
                begin.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun independentServersUseDifferentPortsAndDoNotShareScenarioCounters() {
        withServer(scenarioEngine()) { first, firstClient ->
            withServer(scenarioEngine()) { second, secondClient ->
                assertTrue(first.baseUrl.port != second.baseUrl.port)
                firstClient.get(first.baseUrl, "sequence").use { assertEquals("0", it.body!!.string()) }
                firstClient.get(first.baseUrl, "sequence").use { assertEquals("1", it.body!!.string()) }
                secondClient.get(second.baseUrl, "sequence").use { assertEquals("0", it.body!!.string()) }
            }
        }
    }

    @Test
    fun parallelRequestsFinishCapturedRulesWhileReplacementIsPublishedForNextRequests() {
        val entered = CountDownLatch(10)
        val release = CountDownLatch(1)
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("version", "/version") {
                    matching {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        true
                    }
                    respond { bodyText("old") }
                }
            })
        }
        withServer(engine) { server, client ->
            val executor = Executors.newFixedThreadPool(10)
            try {
                val requests = (0 until 10).map {
                    executor.submit<String> { client.get(server.baseUrl, "version").use { it.body!!.string() } }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                engine.replaceRules(mockRules {
                    rule("version", "/version") { respond { bodyText("replacement") } }
                })
                release.countDown()
                assertEquals(List(10) { "old" }, requests.map { it.get(5, TimeUnit.SECONDS) })
                client.get(server.baseUrl, "version").use { assertEquals("replacement", it.body!!.string()) }
                engine.upsertRule(mockRules {
                    rule("version", "/version") { respond { bodyText("upserted") } }
                }.single())
                client.get(server.baseUrl, "version").use { assertEquals("upserted", it.body!!.string()) }
            } finally {
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun responseDelayTriggersRealReadAndCallTimeouts() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules { rule("slow", "/slow") { respond { delay(30.seconds) } } })
        }
        withServer(engine) { server, _ ->
            val readTimeoutClient = testClient { readTimeout(100, TimeUnit.MILLISECONDS) }
            val callTimeoutClient = testClient {
                readTimeout(10, TimeUnit.SECONDS)
                callTimeout(100, TimeUnit.MILLISECONDS)
            }
            try {
                for (client in listOf(readTimeoutClient, callTimeoutClient)) {
                    assertFailsWith<InterruptedIOException> { client.get(server.baseUrl, "slow").close() }
                }
            } finally {
                readTimeoutClient.closeTestClient()
                callTimeoutClient.closeTestClient()
            }
        }
    }

    @Test
    fun cancellationStopsWaitingClientWhileDelayedRequestDoesNotBlockOtherRequests() {
        val entered = CountDownLatch(1)
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("slow", "/slow") {
                    matching { entered.countDown(); true }
                    respond { delay(30.seconds) }
                }
                rule("fast", "/fast") { respond { bodyText("fast") } }
            })
        }
        withServer(engine) { server, client ->
            val executor = Executors.newSingleThreadExecutor()
            val call = client.newCall(Request.Builder().url(server.baseUrl.resolve("slow")!!).build())
            try {
                val request = executor.submit<Boolean> {
                    try { call.execute().close(); false } catch (_: IOException) { call.isCanceled() }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                client.get(server.baseUrl, "fast").use { assertEquals("fast", it.body!!.string()) }
                call.cancel()
                assertTrue(request.get(2, TimeUnit.SECONDS))
            } finally {
                call.cancel()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun scenarioEngine() = RuleEngine().apply {
        replaceRules(mockRules {
            rule("sequence", "/sequence") {
                roundRobin {
                    repeat(10) { index -> response { bodyText(index.toString()) } }
                }
            }
        })
    }
}
