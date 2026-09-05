package gathedge.backend.tools

import gathedge.backend.config.AppConfig
import gathedge.backend.db.{DataSourceFactory, DbDialect, FlywayMigrator}
import zio.*

import javax.sql.DataSource

/** Applies the Postgres migrations to the schema `db.schema` names, and stops.
  *
  * `Main` does this on every boot, so nothing in production needs it. It exists for the one job that wants a migrated
  * schema without a running server: `scripts/build-dictionary-fixture.sh`, which migrates a scratch schema, loads the
  * committed seed into it, and exports the result for `PostgresIntegrationSpec` to restore.
  *
  * {{{
  * DB_SCHEMA=gathedge_fixture sbt "backend/runMain gathedge.backend.tools.Migrate"
  * }}}
  *
  * It reads the same `DB_URL`/`DB_USER`/`DB_PASSWORD`/`DB_SCHEMA` environment overrides every other entry point does.
  * `.env` reaches `reStart` through `build.sbt`, never `run`, so a caller must export them itself — which is what the
  * script does.
  */
object Migrate extends ZIOAppDefault {

  def run: ZIO[ZIOAppArgs, Any, Any] = {
    val migrated = for {
      config     <- ZIO.service[AppConfig]
      dataSource <- ZIO.service[DataSource]
      _          <- FlywayMigrator.migrate(dataSource, DbDialect.Postgresql, Some(config.db.schema))
      _          <- ZIO.logInfo(s"Migrated schema ${config.db.schema}")
    } yield ()

    migrated.provide(AppConfig.live, DataSourceFactory.postgresLive)
  }
}
