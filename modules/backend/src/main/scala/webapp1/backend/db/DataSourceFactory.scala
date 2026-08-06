package webapp1.backend.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import webapp1.backend.config.AppConfig
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
            hikariConfig.setPoolName("webapp1-postgres")
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
