package dev.androidmock.core.dsl

import dev.androidmock.core.engine.ConfigurationException
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.PATH_SEPARATOR
import dev.androidmock.core.response.ResponseBody
import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.response.STATUS_OK
import dev.androidmock.core.response.CONTENT_TYPE_HEADER
import dev.androidmock.core.response.JSON_UTF8_CONTENT_TYPE
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

private const val MULTIPLE_RESPONSE_BODIES_MESSAGE = "Multiple response bodies"
private const val NON_OBJECT_MODEL_MESSAGE = "JSON model must serialize as an object"
private const val INVALID_JSON_MODEL_MESSAGE = "Invalid JSON model response"
private const val JSON_SERIALIZATION_MESSAGE = "Cannot serialize response JSON"
private const val NO_ASSET_RESOLVER_MESSAGE_PREFIX = "No asset resolver for "
private const val ASSET_READ_MESSAGE_PREFIX = "Cannot read asset: "
private const val INVALID_ASSET_PATH_MESSAGE_PREFIX = "Invalid asset path: "
private const val BACKSLASH = '\\'
private const val CURRENT_DIRECTORY_SEGMENT = "."
private const val PARENT_DIRECTORY_SEGMENT = ".."

@MockDsl
@OptIn(ExperimentalSerializationApi::class)
class ResponseDsl internal constructor(private val assets: BodyAssetResolver?) {

	private companion object {

		val json = Json {
			encodeDefaults = true
			explicitNulls = true
		}
	}

	private var status = STATUS_OK
	private val headers = mutableListOf<HeaderEntry>()
	private var body: ResponseBody = ResponseBody.Empty
	private var bodySet = false
	private var jsonBody = false
	private var delay: Duration = ZERO
	fun status(value: Int) {
		status = value
	}

	fun header(name: String, value: String) {
		headers += HeaderEntry(name, value)
	}

	fun delay(value: Duration) {
		delay = value
	}

	fun bodyText(value: String) = bodyBytes(value.toByteArray(Charsets.UTF_8))
	inline fun <reified T> bodyJson(value: T) = bodyJson(value, serializer<T>())
	inline fun <reified T> bodyJson(noinline block: JsonModelDsl.() -> Unit) = bodyJson(serializer<T>(), block)

	fun <T> bodyJson(serializer: KSerializer<T>, block: JsonModelDsl.() -> Unit) {
		if (bodySet) throw ConfigurationException(MULTIPLE_RESPONSE_BODIES_MESSAGE)
		if (serializer.descriptor.kind != StructureKind.CLASS && serializer.descriptor.kind != StructureKind.OBJECT) {
			throw ConfigurationException(NON_OBJECT_MODEL_MESSAGE)
		}
		val explicit = JsonModelDsl(serializer.descriptor, json).apply(block).build()
		val element = fillMissingJsonFields(serializer.descriptor, explicit)
		val model = try {
			json.decodeFromJsonElement(serializer, element)
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			throw ConfigurationException(INVALID_JSON_MODEL_MESSAGE)
		}
		bodyJson(model, serializer)
	}

	fun <T> bodyJson(value: T, serializer: SerializationStrategy<T>) {
		if (bodySet) throw ConfigurationException(MULTIPLE_RESPONSE_BODIES_MESSAGE)
		val encoded = try {
			json.encodeToString(serializer, value)
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			throw ConfigurationException(JSON_SERIALIZATION_MESSAGE)
		}
		setBody(ResponseBody.Bytes(encoded.toByteArray(Charsets.UTF_8)))
		jsonBody = true
	}

	fun bodyBytes(value: ByteArray) {
		setBody(ResponseBody.Bytes(value))
	}

	fun bodyAsset(path: String) {
		if (bodySet) throw ConfigurationException(MULTIPLE_RESPONSE_BODIES_MESSAGE)
		validateAssetPath(path)
		val resolver = assets ?: throw ConfigurationException("$NO_ASSET_RESOLVER_MESSAGE_PREFIX$path")
		val bytes = try {
			resolver.read(path)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			throw ConfigurationException("$ASSET_READ_MESSAGE_PREFIX$path", e)
		}
		bodyBytes(bytes)
	}

	private fun setBody(value: ResponseBody) {
		if (bodySet) throw ConfigurationException(MULTIPLE_RESPONSE_BODIES_MESSAGE)
		body = value
		bodySet = true
	}

	internal fun build(): ResponseSpec {
		val effectiveHeaders = if (jsonBody && headers.none { it.name.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }) {
			headers + HeaderEntry(CONTENT_TYPE_HEADER, JSON_UTF8_CONTENT_TYPE)
		} else {
			headers
		}
		return ResponseSpec(status, effectiveHeaders, body, delay)
	}
}

private fun validateAssetPath(path: String) {
	if (path.isBlank() || path.startsWith(PATH_SEPARATOR) || BACKSLASH in path || path.split(PATH_SEPARATOR).any {
		it.isEmpty() || it == CURRENT_DIRECTORY_SEGMENT || it == PARENT_DIRECTORY_SEGMENT
	}) {
		throw ConfigurationException("$INVALID_ASSET_PATH_MESSAGE_PREFIX$path")
	}
}
