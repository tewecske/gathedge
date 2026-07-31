package webapp1.backend.db

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import webapp1.backend.config.AppConfig
import zio.*

import javax.sql.DataSource

object DataSourceFactory {

  /** Production Postgres pool, built straight from our own config (not Quill's
    * Hikari-prefix Typesafe-Config convention) so config shape stays under our
    * control.
    */
  val postgresLive: ZLayer[AppConfig, Throwable, DataSource] =
    ZLayer.scoped {
      for {
        cfg <- ZIO.service[AppConfig]
        ds <- ZIO.acquireRelease(ZIO.attempt {
                val hikariConfig = new HikariConfig()
                hikariConfig.setJdbcUrl(cfg.db.url)
                hikariConfig.setUsername(cfg.db.user)
                hikariConfig.setPassword(cfg.db.password)
                hikariConfig.setPoolName("webapp1-postgres")
                new HikariDataSource(hikariConfig)
              })(ds => ZIO.attempt(ds.close()).orDie)
      } yield ds: DataSource
    }
}
