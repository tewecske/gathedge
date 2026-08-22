package gathedge.backend.db

import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource

enum DbDialect {
  case Postgresql,
    Sqlite
}

object FlywayMigrator {

  /** `schema` is an `Option` rather than a `String` because only one dialect has the concept: on Postgres it names the
    * schema this application owns, which Flyway creates if it is missing and puts its own history table in; SQLite has
    * no schemas at all, so the test databases pass `None`. It must be the same value `DataSourceFactory` puts on the
    * pool's connections as their `search_path` — migrating into one schema and querying another fails at the first
    * request, long after this call has reported success.
    *
    * `target` is `None` everywhere except one place: `PostgresIntegrationSpec`'s V14-backfill test, which needs to stop
    * at a specific version (`Some("13")`) to insert pre-migration-shaped rows before letting a second `migrate` call
    * (with `target = None`, i.e. Flyway's own default "latest") apply the migration under test against real data. Every
    * production caller (`Main`, `DictionaryImport`) and every other test always migrates to latest.
    */
  def migrate(
    dataSource: DataSource,
    dialect: DbDialect,
    schema: Option[String],
    target: Option[String] = None,
  ): Task[Unit] = {
    ZIO.attempt {
      val location          = {
        dialect match {
          case DbDialect.Postgresql =>
            "classpath:db/migration/postgresql"
          case DbDialect.Sqlite     =>
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
          case DbDialect.Sqlite     =>
            true
        }
      }
      val configured        = {
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations(location)
          .baselineOnMigrate(baselineOnMigrate)
      }
      // `.schemas` rather than leaving it to the connection's search_path: it is what makes Flyway
      // issue the CREATE SCHEMA on a first boot, and what puts flyway_schema_history inside the
      // schema it manages rather than next to it in public.
      val withSchema        = schema.fold(configured)(name => configured.schemas(name))
      target
        .fold(withSchema)(version => withSchema.target(version))
        .load()
        .migrate()
    }.unit
  }
}
