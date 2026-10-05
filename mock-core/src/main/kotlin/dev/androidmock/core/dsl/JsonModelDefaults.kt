package dev.androidmock.core.dsl

import dev.androidmock.core.engine.ConfigurationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val CYCLIC_MODEL_MESSAGE_PREFIX = "Cannot generate cyclic JSON model: "
private const val UNSUPPORTED_DEFAULT_MESSAGE_PREFIX = "Cannot generate JSON default for "
private const val DEFAULT_JSON_CHAR = "\u0000"

/** Generate only required values; optional fields are left for the model decoder to supply. */
@OptIn(ExperimentalSerializationApi::class)
internal fun fillMissingJsonFields(descriptor: SerialDescriptor, explicit: JsonObject): JsonObject =
	jsonObjectDefaults(descriptor, explicit, emptySet())

@OptIn(ExperimentalSerializationApi::class)
private fun jsonObjectDefaults(
	descriptor: SerialDescriptor,
	explicit: JsonObject,
	activeTypes: Set<String>,
): JsonObject {
	if (descriptor.serialName in activeTypes) {
		throw ConfigurationException("$CYCLIC_MODEL_MESSAGE_PREFIX${descriptor.serialName}")
	}
	val active = activeTypes + descriptor.serialName
	val fields = LinkedHashMap(explicit)
	for (index in 0 until descriptor.elementsCount) {
		val name = descriptor.getElementName(index)
		if (name !in fields && !descriptor.isElementOptional(index)) {
			fields[name] = defaultJsonValue(descriptor.getElementDescriptor(index), active)
		}
	}
	return JsonObject(fields)
}

@OptIn(ExperimentalSerializationApi::class)
private fun defaultJsonValue(descriptor: SerialDescriptor, activeTypes: Set<String>): JsonElement {
	if (descriptor.isNullable) return JsonNull
	return when (descriptor.kind) {
		PrimitiveKind.BOOLEAN                                                          -> JsonPrimitive(false)
		PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> JsonPrimitive(0)
		PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE                                      -> JsonPrimitive(0.0)
		PrimitiveKind.CHAR                                                             -> JsonPrimitive(DEFAULT_JSON_CHAR)
		PrimitiveKind.STRING                                                           -> JsonPrimitive("")

		SerialKind.ENUM                                                                -> {
			if (descriptor.elementsCount == 0) throw unsupportedDefault(descriptor)
			JsonPrimitive(descriptor.getElementName(0))
		}

		StructureKind.CLASS, StructureKind.OBJECT                                      -> {
			if (descriptor.isInline) throw unsupportedDefault(descriptor)
			jsonObjectDefaults(descriptor, JsonObject(emptyMap()), activeTypes)
		}

		StructureKind.LIST                                                             -> JsonArray(emptyList())
		StructureKind.MAP                                                              -> JsonObject(emptyMap())
		else                                                                           -> throw unsupportedDefault(descriptor)
	}
}

@OptIn(ExperimentalSerializationApi::class)
private fun unsupportedDefault(descriptor: SerialDescriptor): ConfigurationException =
	ConfigurationException("$UNSUPPORTED_DEFAULT_MESSAGE_PREFIX${descriptor.serialName}")
