package io.titlis.api.config

import io.ktor.server.config.ApplicationConfig

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
                    url = db.property("url").getString(),
                    user = db.property("user").getString(),
                    password = db.property("password").getString(),
                    maxPoolSize = db.property("pool.maxPoolSize").getString().toInt(),
                    connectionTimeout = db.property("pool.connectionTimeout").getString().toLong(),
                    idleTimeout = db.property("pool.idleTimeout").getString().toLong(),
                ),
                udp = UdpConfig(
                    port = udp.property("port").getString().toInt(),
                    bufferSize = udp.property("bufferSize").getString().toInt(),
                    workers = udp.property("workers").getString().toInt(),
                    queueSize = udp.property("queueSize").getString().toInt(),
                ),
            )
        }
    }
}
