package gathedge.backend.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import gathedge.backend.config.AppConfig
import zio.*

import javax.sql.DataSource

object DataSourceFactory {

  /** Production Postgres pool, built straight from our own config (not Quill's Hikari-prefix Typesafe-Config
    * convention) so config shape stays under our control.
    */
  val postgresLive: ZLayer[AppConfig, Throwable, DataSource] = ZLayer.scoped {
    for {
      cfg <- ZIO.service[AppConfig]
      ds  <-
        ZIO.acquireRelease(
          ZIO.attempt {
            val hikariConfig = new HikariConfig()
            hikariConfig.setJdbcUrl(cfg.db.url)
            hikariConfig.setUsername(cfg.db.user)
            hikariConfig.setPassword(cfg.db.password)
            hikariConfig.setPoolName("gathedge-postgres")
            // The runtime half of `db.schema`, whose other half is the schema `FlywayMigrator` migrates
            // into — the two read one config key and must stay that way. Hikari calls
            // `Connection.setSchema` on every connection it hands out, which pgjdbc issues as
            // `SET SESSION search_path`, and that is what resolves the unqualified table names in every
            // repository. Setting it here rather than as `?currentSchema=` in the URL keeps the name in
            // one place instead of buried in `DB_URL`. Harmless before the schema exists: Postgres
            // accepts a search_path naming one that does not, and simply skips it until Flyway's
            // CREATE SCHEMA lands a few steps later in `Main`.
            hikariConfig.setSchema(cfg.db.schema)
            // Explicit rather than implicit: a request that cannot get a connection should fail in
            // seconds with a clear error instead of hanging on the pool, and a connection held past
            // the leak threshold (a transaction that never finished) should leave a stack trace in
            // the log rather than quietly shrinking the pool.
            hikariConfig.setMaximumPoolSize(10)
            hikariConfig.setConnectionTimeout(5000)
            hikariConfig.setLeakDetectionThreshold(30000)
            new HikariDataSource(hikariConfig)
          }
        )(ds => ZIO.attempt(ds.close()).orDie)
    } yield ds: DataSource
  }
}
