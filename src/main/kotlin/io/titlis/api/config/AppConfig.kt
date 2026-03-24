import com.typesafe.config.Config

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
        fun from(config: Config): AppConfig {
            val db = config.getConfig("titlis.database")
            val udp = config.getConfig("titlis.udp")
            return AppConfig(
                database = DatabaseConfig(
                    url = db.getString("url"),
                    user = db.getString("user"),
                    password = db.getString("password"),
                    maxPoolSize = db.getInt("pool.maxPoolSize"),
                    connectionTimeout = db.getLong("pool.connectionTimeout"),
                    idleTimeout = db.getLong("pool.idleTimeout"),
                ),
                udp = UdpConfig(
                    port = udp.getInt("port"),
                    bufferSize = udp.getInt("bufferSize"),
                    workers = udp.getInt("workers"),
                    queueSize = udp.getInt("queueSize"),
                ),
            )
        }
    }
}