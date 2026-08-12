package gathedge.frontend.listing

import com.raquo.waypoint._
import urldsl.vocabulary.Codec
import gathedge.frontend.components.SortHeader
import gathedge.shared.dto.{AuditSort, Paging}

/** Everything that decides which audit entries the server sends back.
  *
  * The two narrowings are applied on a button rather than as they are typed — an administrator id is only meaningful
  * once it is complete — so they are held here alongside the paging rather than being read off the inputs.
  */
final case class AuditQuery(
  page: Int = Paging.firstPage,
  pageSize: Int = Paging.defaultPageSize,
  sort: SortHeader.Sort = SortHeader.Sort.unsorted,
  action: Option[String] = None,
  actorId: Option[Long] = None,
) {

  /** Anything but turning the page starts again at the first one. */
  def reset(change: AuditQuery => AuditQuery): AuditQuery = change(this).copy(page = Paging.firstPage)
}

object AuditQuery {

  val default: AuditQuery = AuditQuery()

  private type Args = (Option[Int], Option[Int], Option[String], Option[String], Option[String], Option[String])

  /** The administrator id travels as a string rather than a `Long`: url-dsl has no `FromString[Long, _]`, and reading
    * it here means a URL with `actor=nonsense` still opens the trail instead of falling through to Not Found.
    */
  private val codec: Codec[Args, AuditQuery] = {
    Codec.factory(
      (args: Args) => {
        val (page, size, sort, direction, action, actor) = args
        AuditQuery(
          page = ListingParams.decodePage(page),
          pageSize = ListingParams.decodePageSize(size),
          sort = ListingParams.decodeSort(sort, direction, AuditSort.all),
          action = ListingParams.decodeText(action),
          actorId = ListingParams.decodeText(actor).flatMap(_.toLongOption),
        )
      },
      (query: AuditQuery) => {
        val (page, size, sort, direction) = ListingParams.encodeCommon(query.page, query.pageSize, query.sort)
        (page, size, sort, direction, query.action, query.actorId.map(_.toString))
      },
    )
  }

  val params = {
    (ListingParams.common & param[String]("action").? & param[String]("actor").?).as[AuditQuery](using codec)
  }
}
