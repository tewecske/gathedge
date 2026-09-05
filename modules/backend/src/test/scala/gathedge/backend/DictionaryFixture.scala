package gathedge.backend

import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import zio.*

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

import javax.sql.DataSource

/** Loads the committed dictionary into a freshly migrated schema.
  *
  * The fixture is `modules/backend/src/test/resources/dictionary-fixture.tsv.gz`, built once by
  * `scripts/build-dictionary-fixture.sh` and committed. It holds '''data only''': the three dictionary tables as
  * Postgres COPY text, each section headed by `#table<TAB>name<TAB>col,col,...`.
  *
  * Data only is the load-bearing half. The structure comes from Flyway at restore time, so a migration that adds a
  * column needs no new fixture — COPY names the columns the file holds, and the new one takes its default. A migration
  * that renames or drops one fails here loudly, which is exactly right: the fixture is then stale and the script has to
  * run again.
  *
  * Why a fixture at all, rather than the spec reading `data/dictionary/seed.tsv`: the expensive half of
  * `DictionaryImport` is not the reading, it is deduping homographs, pivoting German–Hungarian pairs through a shared
  * English sense, and deriving form-to-form edges. That work is done once, in the script, not once per test run.
  */
object DictionaryFixture {

  private val resource = "/dictionary-fixture.tsv.gz"

  /** Marks the start of a table's rows. Tab-separated so it can never collide with COPY text, whose own rows escape
    * every tab and newline they contain.
    */
  private val marker = "#table\t"

  /** COPYs every section of the fixture into `schema`, then advances each table's identity sequence past the ids it
    * just wrote.
    *
    * The sequences matter as much as the rows: `id BIGINT GENERATED ALWAYS AS IDENTITY` keeps its own counter, COPY
    * writes the file's ids straight past it (COPY FROM overrides a system-generated identity, unlike INSERT), and a
    * test that then creates a word would collide with row 1 on the primary key.
    */
  def restore(dataSource: DataSource, schema: String): Task[Int] = {
    ZIO.attemptBlocking {
      val stream     = Option(getClass.getResourceAsStream(resource)).getOrElse {
        throw new IllegalStateException(
          s"$resource is missing from the test classpath — run scripts/build-dictionary-fixture.sh"
        )
      }
      val reader     = new BufferedReader(
        new InputStreamReader(new GZIPInputStream(stream), StandardCharsets.UTF_8)
      )
      val connection = dataSource.getConnection

      try {
        val copyManager = new CopyManager(connection.unwrap(classOf[BaseConnection]))
        val tables      = List.newBuilder[String]
        var rows        = 0
        var line        = reader.readLine()

        while (line != null) {
          if (!line.startsWith(marker)) {
            throw new IllegalStateException(s"$resource: expected a $marker header, got: ${line.take(60)}")
          }
          val Array(_, table, columns) = line.split("\t", 3): @unchecked
          tables += table

          // One COPY per section, fed line by line so the whole dictionary never has to be held in memory at once.
          val copy = copyManager.copyIn(s"""COPY "$schema"."$table" ($columns) FROM STDIN""")
          line = reader.readLine()
          while (line != null && !line.startsWith(marker)) {
            val bytes = (line + "\n").getBytes(StandardCharsets.UTF_8)
            copy.writeToCopy(bytes, 0, bytes.length)
            rows += 1
            line = reader.readLine()
          }
          copy.endCopy()
        }

        // Every one of these tables is keyed by an identity column of the same name.
        val statement = connection.createStatement()
        try {
          tables.result().foreach { table =>
            statement.execute(
              s"""SELECT setval(
                 |  pg_get_serial_sequence('"$schema"."$table"', 'id'),
                 |  COALESCE((SELECT max(id) FROM "$schema"."$table"), 1)
                 |)""".stripMargin
            )
          }
        } finally statement.close()

        rows
      } finally {
        connection.close()
        reader.close()
      }
    }
  }
}
