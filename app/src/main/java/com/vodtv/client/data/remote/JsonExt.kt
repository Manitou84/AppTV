package com.vodtv.client.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Helper tolleranti: il formato esatto delle risposte non è documentato,
 * quindi ogni campo viene cercato fra più nomi possibili.
 */

internal fun JsonObject.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.int(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.intOrNull }

internal fun JsonObject.long(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.longOrNull }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Trova la prima lista utile: array in radice, oppure sotto chiavi note, oppure il primo array presente. */
internal fun JsonElement.findList(vararg preferredKeys: String): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> preferredKeys.firstNotNullOfOrNull { this[it] as? JsonArray }
        ?: (obj("data")?.findList(*preferredKeys))?.takeIf { it.isNotEmpty() }
        ?: values.firstOrNull { it is JsonArray } as? JsonArray
        ?: emptyList()
    else -> emptyList()
}
