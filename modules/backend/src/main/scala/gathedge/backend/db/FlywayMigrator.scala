package gathedge.backend.db

import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource

object FlywayMigrator {

  /** `schema` is an `Option` because Flyway's `.schemas` call is what makes it issue the `CREATE SCHEMA` on a first
    * boot and put `flyway_schema_history` inside the schema it manages rather than next to it in `public` — omitting it
    * (`None`) leaves Flyway on whatever schema the connection's own `search_path` already names. It must be the same
    * value `DataSourceFactory` puts on the pool's connections as their `search_path` — migrating into one schema and
    * querying another fails at the first request, long after this call has reported success.
    *
    * `target` is `None` everywhere except one place: `PostgresIntegrationSpec`'s V14-backfill test, which needs to stop
    * at a specific version (`Some("13")`) to insert pre-migration-shaped rows before letting a second `migrate` call
    * (with `target = None`, i.e. Flyway's own default "latest") apply the migration under test against real data. Every
    * production caller (`Main`, `DictionaryImport`) and every other test always migrates to latest.
    *
    * `baselineOnMigrate` stays off: every schema this ever runs against, prod or test, starts empty and gets Flyway's
    * own history table — a non-empty schema with no history table means someone applied migrations out of band, and
    * baselining there would mark every version applied and skip it silently. Fail instead and let a human look.
    */
  def migrate(dataSource: DataSource, schema: Option[String], target: Option[String] = None): Task[Unit] = {
    ZIO.attempt {
      val configured = {
        Flyway
          .configure()
          .dataSource(dataSource)
          .locations("classpath:db/migration/postgresql")
          .baselineOnMigrate(false)
      }
      val withSchema = schema.fold(configured)(name => configured.schemas(name))
      target
        .fold(withSchema)(version => withSchema.target(version))
        .load()
        .migrate()
    }.unit
  }
}
