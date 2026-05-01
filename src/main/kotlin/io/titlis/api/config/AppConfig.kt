package io.titlis.api.config

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ApplicationConfigurationException

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
    val minIdle: Int,
    val connectionTimeout: Long,
    val idleTimeout: Long,
    val maxLifetime: Long,
)

data class DatabaseMigrationConfig(
    val url: String,
    val user: String,
    val password: String,
)

data class UdpConfig(
    val port: Int,
    val bufferSize: Int,
    val workers: Int,
    val queueSize: Int,
)

data class AuthConfig(
    val appEnv: String,
    val issuer: String,
    val audience: String,
    val accessTokenSecret: String,
    val accessTokenTtlMinutes: Long,
    val oktaIssuer: String?,
    val oktaAudience: String?,
    val oktaClientId: String?,
    val authMode: String,
    val devBypassEnabled: Boolean,
    val devTenantId: Long,
    val devUserEmail: String,
    val devRoles: List<String>,
)

data class AiServiceConfig(
    val url: String,
    val internalSecret: String,
)

data class AppConfig(
    val database: DatabaseConfig,
    val databaseMigration: DatabaseMigrationConfig,
    val udp: UdpConfig,
    val auth: AuthConfig,
    val corsAllowedOrigins: List<String>,
    val aiService: AiServiceConfig,
) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            val db = config.config("titlis.database")
            val udp = config.config("titlis.udp")
            val auth = config.config("titlis.auth")
            return AppConfig(
                database = DatabaseConfig(
                    url = propertyOrEnv(
                        config = db,
                        path = "url",
                        env = "DATABASE_URL",
                        default = "jdbc:postgresql://localhost:5432/titlis",
                    ),
                    user = propertyOrEnv(
                        config = db,
                        path = "user",
                        env = "DATABASE_USER",
                        default = "titlis",
                    ),
                    password = propertyOrEnv(
                        config = db,
                        path = "password",
                        env = "DATABASE_PASSWORD",
                        default = "titlis",
                    ),
                    maxPoolSize = propertyOrEnv(
                        config = db,
                        path = "pool.maxPoolSize",
                        env = "DB_POOL_MAX",
                        default = "5",
                    ).toInt(),
                    minIdle = propertyOrEnv(
                        config = db,
                        path = "pool.minIdle",
                        env = "DB_POOL_MIN_IDLE",
                        default = "2",
                    ).toInt(),
                    connectionTimeout = propertyOrEnv(
                        config = db,
                        path = "pool.connectionTimeout",
                        env = "DB_POOL_CONNECTION_TIMEOUT",
                        default = "30000",
                    ).toLong(),
                    idleTimeout = propertyOrEnv(
                        config = db,
                        path = "pool.idleTimeout",
                        env = "DB_POOL_IDLE_TIMEOUT",
                        default = "600000",
                    ).toLong(),
                    maxLifetime = propertyOrEnv(
                        config = db,
                        path = "pool.maxLifetime",
                        env = "DB_POOL_MAX_LIFETIME",
                        default = "1800000",
                    ).toLong(),
                ),
                databaseMigration = DatabaseMigrationConfig(
                    url = propertyOrEnv(
                        config = db,
                        path = "url",
                        env = "DATABASE_URL",
                        default = "jdbc:postgresql://localhost:5432/titlis",
                    ),
                    user = propertyOrEnv(
                        config = db,
                        path = "migration.user",
                        env = "DATABASE_MIGRATION_USER",
                        default = "titlis",
                    ),
                    password = propertyOrEnv(
                        config = db,
                        path = "migration.password",
                        env = "DATABASE_MIGRATION_PASSWORD",
                        default = "titlis",
                    ),
                ),
                udp = UdpConfig(
                    port = propertyOrEnv(
                        config = udp,
                        path = "port",
                        env = "TITLIS_UDP_PORT",
                        default = "8125",
                    ).toInt(),
                    bufferSize = propertyOrEnv(
                        config = udp,
                        path = "bufferSize",
                        env = "TITLIS_UDP_BUFFER_SIZE",
                        default = "65507",
                    ).toInt(),
                    workers = propertyOrEnv(
                        config = udp,
                        path = "workers",
                        env = "TITLIS_UDP_WORKERS",
                        default = "4",
                    ).toInt(),
                    queueSize = propertyOrEnv(
                        config = udp,
                        path = "queueSize",
                        env = "TITLIS_UDP_QUEUE_SIZE",
                        default = "10000",
                    ).toInt(),
                ),
                corsAllowedOrigins = propertyOrEnv(
                    config = config,
                    path = "titlis.corsAllowedOrigins",
                    env = "TITLIS_CORS_ALLOWED_ORIGINS",
                    default = "http://localhost:13000",
                ).split(",").map(String::trim).filter(String::isNotBlank),
                aiService = AiServiceConfig(
                    url = propertyOrEnv(
                        config = config,
                        path = "titlis.aiService.url",
                        env = "TITLIS_AI_URL",
                        default = "http://titlis-ai:8001",
                    ),
                    internalSecret = propertyOrEnv(
                        config = config,
                        path = "titlis.aiService.internalSecret",
                        env = "TITLIS_AI_INTERNAL_SECRET",
                        default = "titlis-ai-internal-secret-dev",
                    ),
                ),
                auth = AuthConfig(
                    appEnv = propertyOrEnv(
                        config = auth,
                        path = "appEnv",
                        env = "TITLIS_APP_ENV",
                        default = "local",
                    ),
                    issuer = propertyOrEnv(
                        config = auth,
                        path = "issuer",
                        env = "TITLIS_AUTH_ISSUER",
                        default = "titlis-local",
                    ),
                    audience = propertyOrEnv(
                        config = auth,
                        path = "audience",
                        env = "TITLIS_AUTH_AUDIENCE",
                        default = "titlis-ui",
                    ),
                    accessTokenSecret = propertyOrEnv(
                        config = auth,
                        path = "accessTokenSecret",
                        env = "TITLIS_AUTH_ACCESS_TOKEN_SECRET",
                        default = "titlis-dev-secret-change-me",
                    ),
                    accessTokenTtlMinutes = propertyOrEnv(
                        config = auth,
                        path = "accessTokenTtlMinutes",
                        env = "TITLIS_AUTH_ACCESS_TOKEN_TTL_MINUTES",
                        default = "720",
                    ).toLong(),
                    oktaIssuer = optionalPropertyOrEnv(
                        config = auth,
                        path = "oktaIssuer",
                        env = "TITLIS_OKTA_ISSUER",
                    ),
                    oktaAudience = optionalPropertyOrEnv(
                        config = auth,
                        path = "oktaAudience",
                        env = "TITLIS_OKTA_AUDIENCE",
                    ),
                    oktaClientId = optionalPropertyOrEnv(
                        config = auth,
                        path = "oktaClientId",
                        env = "TITLIS_OKTA_CLIENT_ID",
                    ),
                    authMode = propertyOrEnv(
                        config = auth,
                        path = "authMode",
                        env = "TITLIS_AUTH_MODE",
                        default = "mixed",
                    ),
                    devBypassEnabled = propertyOrEnv(
                        config = auth,
                        path = "devBypassEnabled",
                        env = "TITLIS_DEV_BYPASS_ENABLED",
                        default = "true",
                    ).toBooleanStrictOrNull() ?: true,
                    devTenantId = propertyOrEnv(
                        config = auth,
                        path = "devTenantId",
                        env = "TITLIS_DEV_TENANT_ID",
                        default = "1",
                    ).toLong(),
                    devUserEmail = propertyOrEnv(
                        config = auth,
                        path = "devUserEmail",
                        env = "TITLIS_DEV_USER_EMAIL",
                        default = "dev@titlis.local",
                    ),
                    devRoles = propertyOrEnv(
                        config = auth,
                        path = "devRoles",
                        env = "TITLIS_DEV_ROLES",
                        default = "Jeitto Confia - Admin",
                    ).split(",").map(String::trim).filter(String::isNotBlank),
                ),
            )
        }

        private fun propertyOrEnv(
            config: ApplicationConfig,
            path: String,
            env: String,
            default: String,
        ): String {
            val configured = try {
                config.property(path).getString()
            } catch (_: ApplicationConfigurationException) {
                null
            }

            return configured
                ?: System.getenv(env)
                ?: default
        }

        private fun optionalPropertyOrEnv(
            config: ApplicationConfig,
            path: String,
            env: String,
        ): String? {
            val configured = try {
                config.property(path).getString()
            } catch (_: ApplicationConfigurationException) {
                null
            }

            return configured
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        }
    }
}
