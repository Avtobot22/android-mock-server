package dev.androidmock.core.dsl

import dev.androidmock.core.engine.ConfigurationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.util.concurrent.CancellationException

private const val FIELD_SERIALIZATION_MESSAGE_PREFIX = "Cannot serialize JSON field: "
private const val NON_OBJECT_FIELD_MESSAGE_PREFIX = "JSON field is not an object: "
private const val DUPLICATE_FIELD_MESSAGE_PREFIX = "Duplicate JSON field: "
private const val UNKNOWN_FIELD_MESSAGE_PREFIX = "Unknown JSON field: "

/** Values are encoded with their generated serializers and checked against the model descriptor. */
@MockDsl
@OptIn(ExperimentalSerializationApi::class)
class JsonModelDsl internal constructor(
	private val descriptor: SerialDescriptor,
	private val json: Json,
) {

	private val fields = linkedMapOf<String, JsonElement>()

	inline fun <reified T> field(name: String, value: T) = field(name, value, serializer<T>())

	fun field(name: String, @Suppress("UNUSED_PARAMETER") value: Nothing?) {
		put(name, JsonNull)
	}

	fun <T> field(name: String, value: T, serializer: SerializationStrategy<T>) {
		checkFieldAvailable(name)
		val encoded = try {
			json.encodeToJsonElement(serializer, value)
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			throw ConfigurationException("$FIELD_SERIALIZATION_MESSAGE_PREFIX$name")
		}
		put(name, encoded)
	}

	fun objectField(name: String, block: JsonModelDsl.() -> Unit) {
		val nested = descriptor.getElementDescriptor(checkFieldAvailable(name))
		if (nested.kind != StructureKind.CLASS && nested.kind != StructureKind.OBJECT || nested.isInline) {
			throw ConfigurationException("$NON_OBJECT_FIELD_MESSAGE_PREFIX$name")
		}
		val explicit = JsonModelDsl(nested, json).apply(block).build()
		put(name, fillMissingJsonFields(nested, explicit))
	}

	private fun put(name: String, value: JsonElement) {
		checkFieldAvailable(name)
		fields[name] = value
	}

	private fun checkFieldAvailable(name: String): Int {
		val index = fieldIndex(name)
		if (name in fields) {
			throw ConfigurationException("$DUPLICATE_FIELD_MESSAGE_PREFIX$name")
		}
		return index
	}

	private fun fieldIndex(name: String): Int = descriptor.getElementIndex(name).also { index ->
		if (index == CompositeDecoder.UNKNOWN_NAME) throw ConfigurationException("$UNKNOWN_FIELD_MESSAGE_PREFIX$name")
	}

	internal fun build(): JsonObject = JsonObject(fields.toMap())
}
