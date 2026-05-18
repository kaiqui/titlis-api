package io.titlis.api.routes

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.titlis.api.repository.ApiKeyRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

suspend fun ApplicationCall.respondJson(value: Any?) {
    respondText(
        text = toJsonElement(value).toString(),
        contentType = ContentType.Application.Json,
    )
}

suspend fun resolveApiKeyTenant(call: ApplicationCall, apiKeyRepo: ApiKeyRepository): Long? {
    val rawToken = call.request.headers["X-Api-Key"] ?: return null
    return apiKeyRepo.resolveByToken(rawToken)
}

private fun toJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(
        value.entries.associate { (key, item) -> key.toString() to toJsonElement(item) },
    )
    is Iterable<*> -> JsonArray(value.map(::toJsonElement))
    is Array<*> -> JsonArray(value.map(::toJsonElement))
    else -> JsonPrimitive(value.toString())
}
