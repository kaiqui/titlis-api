package io.titlis.api.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.titlis.api.auth.AppPrincipal
import io.titlis.api.auth.AuthMeResponse
import io.titlis.api.auth.AuthSessionResponse
import io.titlis.api.auth.BootstrapAlreadyConfiguredException
import io.titlis.api.auth.BootstrapSetupRequest
import io.titlis.api.auth.InvalidCredentialsException
import io.titlis.api.auth.LocalLoginRequest
import io.titlis.api.auth.LocalTokenService
import io.titlis.api.auth.protectedProviderNames
import io.titlis.api.auth.RequestAuthenticator
import io.titlis.api.auth.TenantRegistrationConflictException
import io.titlis.api.repository.AuthRepository

fun Application.authRoutes(
    repo: AuthRepository,
    tokenService: LocalTokenService,
    requestAuthenticator: RequestAuthenticator,
) {
    routing {
        route("/v1/auth") {
            get("/bootstrap/status") {
                call.respond(repo.bootstrapStatus())
            }

            post("/bootstrap/setup") {
                try {
                    val request = call.receive<BootstrapSetupRequest>()
                    val user = repo.setupBootstrap(request)
                    val token = tokenService.issue(user)
                    call.respond(
                        HttpStatusCode.Created,
                        AuthSessionResponse(
                            accessToken = token.value,
                            expiresAt = token.expiresAt.toString(),
                            user = user.toResponse(),
                        ),
                    )
                } catch (cause: TenantRegistrationConflictException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (cause.message ?: "tenant_registration_conflict")))
                } catch (_: BootstrapAlreadyConfiguredException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "bootstrap_already_configured"))
                } catch (cause: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid_request")))
                }
            }

            post("/local/login") {
                try {
                    val request = call.receive<LocalLoginRequest>()
                    val user = repo.authenticateLocal(request)
                    val token = tokenService.issue(user)
                    call.respond(
                        AuthSessionResponse(
                            accessToken = token.value,
                            expiresAt = token.expiresAt.toString(),
                            user = user.toResponse(),
                        ),
                    )
                } catch (_: InvalidCredentialsException) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_credentials"))
                } catch (cause: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "invalid_request")))
                }
            }

            authenticate(*protectedProviderNames("app-auth", "okta-jwt")) {
                get("/me") {
                    val principal = call.principal<AppPrincipal>()
                        ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing_or_invalid_token"))
                    call.respond(AuthMeResponse(user = principal.toResponse()))
                }
            }
        }
    }
}
