package io.titlis.api.config

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ApplicationConfigurationException

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
    val connectionTimeout: Long,
    val idleTimeout: Long,
)

data class UdpConfig(
    val port: Int,
    val bufferSize: Int,
    val workers: Int,
    val queueSize: Int,
)

data class AppConfig(
    val database: DatabaseConfig,
    val udp: UdpConfig,
) {
    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            val db = config.config("titlis.database")
            val udp = config.config("titlis.udp")
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
                        default = "10",
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
    }
}
