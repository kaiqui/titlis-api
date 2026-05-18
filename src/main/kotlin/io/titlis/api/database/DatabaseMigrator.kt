package io.titlis.api.database

import io.titlis.api.config.DatabaseMigrationConfig
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

object DatabaseMigrator {
    private val log = LoggerFactory.getLogger(DatabaseMigrator::class.java)

    fun migrate(config: DatabaseMigrationConfig) {
        log.info("Applying database migrations (user: {})", config.user)
        try {
            val flyway = Flyway.configure()
                .dataSource(config.url, config.user, config.password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .validateOnMigrate(true)
                .load()
            flyway.repair()
            val result = flyway.migrate()
            log.info("Migrations complete: {} applied, target schema version: {}",
                result.migrationsExecuted, result.targetSchemaVersion)
        } catch (e: Exception) {
            log.error("Database migration failed — app will continue but schema may be stale", e)
        }
    }
}
