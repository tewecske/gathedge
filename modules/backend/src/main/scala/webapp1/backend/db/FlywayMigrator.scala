package webapp1.backend.db

import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource

enum DbDialect {
  case Postgresql, Sqlite
}

object FlywayMigrator {

  def migrate(dataSource: DataSource, dialect: DbDialect): Task[Unit] = {
    ZIO.attempt {
      val location = dialect match {
        case DbDialect.Postgresql => "classpath:db/migration/postgresql"
        case DbDialect.Sqlite     => "classpath:db/migration/sqlite"
      }
      Flyway
        .configure()
        .dataSource(dataSource)
        .locations(location)
        .baselineOnMigrate(true)
        .load()
        .migrate()
    }.unit
  }
}
