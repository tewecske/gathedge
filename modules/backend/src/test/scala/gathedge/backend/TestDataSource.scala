package gathedge.backend

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import gathedge.backend.db.{DbDialect, FlywayMigrator}
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
            val tempFile = Files.createTempFile("gathedge-test", ".db")
            tempFile.toFile.deleteOnExit()
            val config   = new HikariConfig()
            config.setJdbcUrl(s"jdbc:sqlite:${tempFile.toAbsolutePath}")
            // Explicit driver class, not DriverManager lookup: tests run unforked in sbt's own JVM, where
            // DriverManager registers the ServiceLoader-discovered drivers exactly once, against whichever
            // test classloader ran first. sbt hands out a new classloader after every recompile, and
            // DriverManager.getDriver then rejects the stale-classloader driver as not visible to the caller
            // ("No suitable driver"), so the second run in a session fails. Naming the class makes Hikari
            // instantiate the driver from the current classloader and never consult the registry.
            config.setDriverClassName("org.sqlite.JDBC")
            config.setMaximumPoolSize(1)
            new HikariDataSource(config)
          }
        )(ds => ZIO.attempt(ds.close()).orDie)
      _  <- FlywayMigrator.migrate(ds, DbDialect.Sqlite)
    } yield ds: DataSource
  }
}
