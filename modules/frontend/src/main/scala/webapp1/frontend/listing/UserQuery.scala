package webapp1.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import webapp1.frontend.components.SortHeader
import webapp1.shared.dto.{Paging, UserSort}

/** Everything that decides which accounts the server sends back, in one value.
  *
  * One state rather than four `Var`s because they are not independent: narrowing the search or reordering the table
  * invalidates the page index, and holding them apart means every writer has to remember to reset it. Being a case
  * class it also compares by value, which is what lets a page ask for a reload only when something actually changed.
  *
  * It lives here rather than in the page because it is also the argument of a route — see [[UserQuery.params]].
  */
final case class UserQuery(
  page: Int = 0,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  search: String = "",
) {

  /** Any change other than turning the page starts again at the first one: page 4 of the old listing says nothing
    * about the new one.
    */
  def reset(change: UserQuery => UserQuery): UserQuery = change(this).copy(page = 0)
}

object UserQuery {

  /** The unfiltered listing — the one addressed by the bare path, with no query string at all. */
  val default: UserQuery = UserQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String])

  private val codec: Codec[Args, UserQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, search) = args
        UserQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, UserSort.all),
          search = ListingParams.decodeText(search).getOrElse(""),
        )
      },
      (query: UserQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (page, size, sort, direction, Option(query.search).filter(_.nonEmpty))
      },
    )
  }

  /** The query half of `/admin/users`. `q` is the search box: a substring of the address, matched case-insensitively
    * by the server.
    */
  val params = (ListingParams.common & param[String]("q").?).as[UserQuery](using codec)
}
