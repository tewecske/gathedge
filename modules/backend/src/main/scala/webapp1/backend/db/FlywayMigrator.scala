package webapp1.backend.db

import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource

enum DbDialect {
  case Postgresql,
    Sqlite
}

object FlywayMigrator {

  def migrate(dataSource: DataSource, dialect: DbDialect): Task[Unit] = {
    ZIO
      .attempt {
        val location = {
          dialect match {
            case DbDialect.Postgresql =>
              "classpath:db/migration/postgresql"
            case DbDialect.Sqlite =>
              "classpath:db/migration/sqlite"
          }
        }
        // Baselining is only wanted for the throwaway SQLite test databases. Against a real
        // Postgres, a non-empty schema with no history table means someone applied migrations
        // out of band: baselining there would mark V1..Vn as applied and skip them silently,
        // so fail instead and let a human look.
        val baselineOnMigrate = {
          dialect match {
            case DbDialect.Postgresql =>
              false
            case DbDialect.Sqlite =>
              true
          }
        }
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations(location)
          .baselineOnMigrate(baselineOnMigrate)
          .load()
          .migrate()
      }
      .unit
  }
}
