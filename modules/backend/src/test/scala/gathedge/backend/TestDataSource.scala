package gathedge.backend

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import gathedge.backend.db.FlywayMigrator
import org.testcontainers.utility.DockerImageName
import zio.*

import javax.sql.DataSource

/** A fresh, migrated Postgres schema per layer instantiation, against one Postgres container shared by the whole test
  * JVM — the test side of the database strategy. Shared by every *ServiceSpec so each test suite gets full isolation
  * without duplicating the setup boilerplate.
  */
object TestDataSource {

  /** Connection details for the one Postgres container the whole test JVM shares.
    *
    * A `lazy val` rather than a `ZLayer.scoped` container-per-spec: sbt runs every `*Spec` class in one JVM (`Test /
    * fork` is left at its default of `false`), so a JVM-wide singleton — started the first time any spec touches it,
    * and left for Testcontainers' own Ryuk reaper to stop when the JVM exits — means every spec file shares one
    * container instead of each starting its own.
    */
  object Container {
    lazy val instance: PostgreSQLContainer = {
      PostgreSQLContainer.Def(dockerImageName = DockerImageName.parse("postgres:16-alpine")).start()
    }
  }

  /** The schema one test (suite) owns, named after `label`.
    *
    * Postgres caps an identifier at 63 bytes, so a long label is cut — and a cut label can collide with another cut to
    * the same prefix, which is what the hash on the end rules out. Lower case throughout: an unquoted identifier folds
    * to lower case anyway, and having the name read the same quoted and unquoted removes a whole class of confusion.
    */
  def schemaFor(label: String): String = {
    val slug = label.toLowerCase.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "").take(40)
    f"t_${slug}_${label.hashCode & 0x7fffffff}%08x"
  }

  def dropSchema(dataSource: DataSource, schema: String): Task[Unit] = {
    ZIO.attemptBlocking {
      val connection = dataSource.getConnection
      try {
        val statement = connection.createStatement()
        try statement.execute(s"""DROP SCHEMA IF EXISTS "$schema" CASCADE""")
        finally statement.close()
      } finally connection.close()
    }
  }

  /** A pool pointed at a schema of its own, named `schemaFor(label)`: created and migrated on the way in, dropped on
    * the way out.
    *
    * The drop is registered *after* the pool, so the finalizers run drop-then-close and the drop still has a connection
    * to run on.
    */
  def postgresSchema(label: String): ZLayer[Any, Throwable, DataSource] = ZLayer.scoped {
    for {
      container <- ZIO.attempt(Container.instance)
      schema     = schemaFor(label)
      ds        <- ZIO.acquireRelease(
                     ZIO.attempt {
                       val hikari = new HikariConfig()
                       hikari.setJdbcUrl(container.jdbcUrl)
                       // Explicit driver class, not DriverManager lookup: tests run unforked in sbt's own JVM, where
                       // DriverManager registers the ServiceLoader-discovered drivers exactly once, against whichever
                       // test classloader ran first. sbt hands out a new classloader after every recompile, and
                       // DriverManager.getDriver then rejects the stale-classloader driver as not visible to the
                       // caller ("No suitable driver"), so the second run in a session fails. Naming the class makes
                       // Hikari instantiate the driver from the current classloader and never consult the registry.
                       hikari.setDriverClassName("org.postgresql.Driver")
                       hikari.setUsername(container.username)
                       hikari.setPassword(container.password)
                       // Set before the schema exists, exactly as production does it: Postgres accepts a search_path
                       // naming a schema that is not there yet and simply skips it until Flyway's CREATE SCHEMA lands.
                       hikari.setSchema(schema)
                       // Small: every spec gets its own pool against one shared container (see Container above), and
                       // a test's own workload is modest and mostly sequential — build.sbt's Test/concurrentRestrictions
                       // caps how many pools exist at once, and this caps how many connections each one holds.
                       hikari.setMaximumPoolSize(2)
                       new HikariDataSource(hikari)
                     }
                   )(pool => ZIO.attempt(pool.close()).orDie)
      _         <- ZIO.acquireRelease(ZIO.unit)(_ => dropSchema(ds, schema).orDie)
      _         <- FlywayMigrator.migrate(ds, Some(schema))
    } yield ds: DataSource
  }

  /** [[postgresSchema]] with a random label — for callers that don't need a debuggable, stable schema name. This is the
    * direct replacement for the old disposable-SQLite-file-per-instantiation layer: every `.provide()` evaluation gets
    * its own fresh, isolated schema.
    */
  val postgres: ZLayer[Any, Throwable, DataSource] =
    ZLayer.suspend(postgresSchema(java.util.UUID.randomUUID().toString))
}
