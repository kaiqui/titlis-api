package io.titlis.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.titlis.api.repository.SloRepository

fun Application.sloRoutes(repo: SloRepository) {
    routing {
        route("/v1") {
            get("/namespaces/{namespace}/slos/{name}") {
                val namespace = call.parameters["namespace"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        "namespace required",
                    )
                val name = call.parameters["name"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "name required")
                val result = repo.getByName(namespace, name)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondJson(result)
            }
        }
    }
}
