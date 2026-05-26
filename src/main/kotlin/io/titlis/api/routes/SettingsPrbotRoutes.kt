package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.auth.protectedProviderNames

// titlis-prbot foi descontinuado. Remediações via GitHub agora são realizadas
// de forma conversacional pelo titlis-ai usando MCP GitHub.
// Todos os endpoints retornam 410 Gone.

private val goneBody = mapOf(
    "error" to "gone",
    "message" to "Funcionalidade descontinuada. Use o assistente IA para configurar remediações.",
)

fun Application.settingsPrbotRoutes() {
    routing {
        authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
            get("/v1/settings/auto-remediation")          { call.respond(HttpStatusCode.Gone, goneBody) }
            put("/v1/settings/auto-remediation")          { call.respond(HttpStatusCode.Gone, goneBody) }
            get("/v1/settings/gitops-profiles")           { call.respond(HttpStatusCode.Gone, goneBody) }
            put("/v1/settings/gitops-profiles")           { call.respond(HttpStatusCode.Gone, goneBody) }
            get("/v1/mappings")                           { call.respond(HttpStatusCode.Gone, goneBody) }
            post("/v1/mappings/refresh")                  { call.respond(HttpStatusCode.Gone, goneBody) }
            get("/v1/settings/github-app/install-url")   { call.respond(HttpStatusCode.Gone, goneBody) }
        }
    }
}
