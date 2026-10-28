package dev.androidmock.okhttp.server

import dev.androidmock.core.engine.RuleEngine
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal fun testClient(builder: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
    OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .callTimeout(5, TimeUnit.SECONDS)
        .apply(builder)
        .build()

internal fun OkHttpClient.closeTestClient() {
    connectionPool.evictAll()
    dispatcher.executorService.shutdownNow()
    check(dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
    cache?.close()
}

internal inline fun withServer(
    engine: RuleEngine,
    logicalBaseUrl: String? = null,
    maxRequestBytes: Long = 1_048_576L,
    block: (LoopbackMockServer, OkHttpClient) -> Unit,
) {
    LoopbackMockServer(engine, logicalBaseUrl, maxRequestBytes).use { server ->
        server.start()
        val client = testClient()
        try {
            block(server, client)
        } finally {
            client.closeTestClient()
        }
    }
}

internal fun OkHttpClient.get(baseUrl: HttpUrl, path: String): Response =
    newCall(Request.Builder().url(baseUrl.resolve(path)!!).build()).execute()
