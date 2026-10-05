package dev.androidmock.sample

import android.content.Context
import dev.androidmock.core.dsl.BodyAssetResolver
import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.ktor.mockKtorEngine
import dev.androidmock.okhttp.MockInterceptor
import io.ktor.client.HttpClient
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream

private const val BODY_ASSET_DIRECTORY = "mock/bodies"
private const val ASSET_BUFFER_BYTES = 8192
private const val ASSET_TOO_LARGE_MESSAGE = "Mock asset too large"
private const val RECEIPT_RULE_ID = "receipt"
private const val RECEIPT_PATH = "/v1/receipt"
private const val RECEIPT_ASSET = "receipt.json"
private const val GET_METHOD = "GET"
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val JSON_CONTENT_TYPE = "application/json"

/** Example composition root. Build rules off the main thread before creating clients. */
fun createDebugMocks(context: Context, onAssetRead: () -> Unit = {}): DebugMocks {
	val engine = RuleEngine()
	val resolver = BodyAssetResolver { path ->
		onAssetRead()
		context.assets.open("$BODY_ASSET_DIRECTORY/$path").use { input ->
			val output = ByteArrayOutputStream()
			val chunk = ByteArray(ASSET_BUFFER_BYTES)
			while (true) {
				val count = input.read(chunk)
				if (count < 0) break
				require(output.size() + count <= engine.limits.maxResponseBytes) { ASSET_TOO_LARGE_MESSAGE }
				output.write(chunk, 0, count)
			}
			output.toByteArray()
		}
	}
	val rules = mockRules(resolver) {
		rule(RECEIPT_RULE_ID, RECEIPT_PATH) {
			match { method(GET_METHOD) }
			respond {
				header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)
				bodyAsset(RECEIPT_ASSET)
			}
		}
	}
	engine.replaceRules(rules)
	return DebugMocks(
		engine, OkHttpClient.Builder().addInterceptor(MockInterceptor(engine)).build(),
		HttpClient(mockKtorEngine(engine))
	)
}

class DebugMocks(val engine: RuleEngine, val okHttp: OkHttpClient, val ktor: HttpClient) : AutoCloseable {

	override fun close() {
		ktor.close()
		okHttp.dispatcher.executorService.shutdown()
	}
}
