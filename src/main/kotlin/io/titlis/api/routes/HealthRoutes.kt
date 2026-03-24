package io.titlis.api.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.healthRoutes() {
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "titlis-api"))
        }
        get("/ready") {
            // Verificar conexão com DB antes de responder 200
            call.respond(mapOf("status" to "ready"))
        }
    }
}