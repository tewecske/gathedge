package webapp1.backend

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import webapp1.backend.db.{DbDialect, FlywayMigrator}
import zio.*

import java.nio.file.Files
import javax.sql.DataSource

/** A fresh, migrated SQLite DB per layer instantiation — the test side of the dual-dialect DB strategy. Shared by every
  * *ServiceSpec so each test gets full isolation without duplicating the setup boilerplate.
  */
object TestDataSource {
  val sqlite: ZLayer[Any, Throwable, DataSource] = ZLayer.scoped {
    for {
      ds <-
        ZIO.acquireRelease(
          ZIO.attempt {
            val tempFile = Files.createTempFile("webapp1-test", ".db")
            tempFile.toFile.deleteOnExit()
            val config = new HikariConfig()
            config.setJdbcUrl(s"jdbc:sqlite:${tempFile.toAbsolutePath}")
            config.setMaximumPoolSize(1)
            new HikariDataSource(config)
          }
        )(ds => ZIO.attempt(ds.close()).orDie)
      _ <- FlywayMigrator.migrate(ds, DbDialect.Sqlite)
    } yield ds: DataSource
  }
}
