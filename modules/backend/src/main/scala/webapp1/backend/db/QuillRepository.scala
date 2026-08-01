package webapp1.backend.db

import io.getquill.NamingStrategy
import io.getquill.context.qzio.ZioJdbcContext
import io.getquill.context.sql.idiom.SqlIdiom
import zio.*

import javax.sql.DataSource

/** Plumbing shared by every Quill-backed repository: it discharges the `DataSource` requirement that `ctx.run`
  * produces, so repository methods can expose a plain `Task`.
  *
  * '''Transactions do not compose across repositories.''' `ZioJdbcContext.transaction` publishes the open connection
  * through `currentConnection`, which is a `FiberRef` held per context *instance* (see ZioJdbcContext.scala in
  * quill-jdbc-zio). Each repository is wired with its own context, so a query issued through a different repository
  * inside `transaction { ... }` quietly takes its own connection and does not take part in the unit of work — it would
  * neither roll back nor see the uncommitted rows. Any set of writes that has to be atomic therefore belongs in the
  * single repository that owns those tables, using [[transaction]] below.
  */
abstract class QuillRepository[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  protected val ctx: ZioJdbcContext[Dialect, Naming],
) {

  protected def run[T](query: ZIO[DataSource, Throwable, T]): Task[T] = {
    query.provideEnvironment(ZEnvironment(dataSource))
  }

  /** Runs several queries of *this* repository as one unit of work; any failure rolls the lot back. */
  protected def transaction[T](queries: ZIO[DataSource, Throwable, T]): Task[T] = {
    run(ctx.transaction(queries))
  }
}
