package gathedge.shared.api

import gathedge.shared.dto.{
  CreateGroupRequest,
  GroupDetail,
  GroupMemberSummary,
  GroupPage,
  InviteCodeResponse,
  JoinGroupRequest,
  RenameGroupRequest,
  SetMemberRoleRequest,
}
import zio.http.Status
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, withCodecError}
import ApiSchemas.given

/** The method and path of every group call, written once. [[GroupEndpoints]] builds its routes from these; the frontend
  * fills them in. No zio-http here, so the frontend can load this object.
  */
object GroupPaths {

  import ApiMethod.*

  val list                 = ApiPath0(GET, "/api/groups")
  val get                  = ApiPath1[Long](GET, "/api/groups/{groupId}")
  val create               = ApiPath0(POST, "/api/groups")
  val join                 = ApiPath0(POST, "/api/groups/join")
  val leave                = ApiPath1[Long](POST, "/api/groups/{groupId}/leave")
  val renameGroup          = ApiPath1[Long](PUT, "/api/groups/{groupId}")
  val regenerateInviteCode = ApiPath1[Long](POST, "/api/groups/{groupId}/invite-code/regenerate")
  val setMemberRole        = ApiPath2[Long, Long](PUT, "/api/groups/{groupId}/members/{userId}/role")
  val removeMember         = ApiPath2[Long, Long](DELETE, "/api/groups/{groupId}/members/{userId}")
  val deleteGroup          = ApiPath1[Long](DELETE, "/api/groups/{groupId}")
  val attachTag            = ApiPath2[Long, Long](PUT, "/api/groups/{groupId}/tags/{tagId}")
  val detachTag            = ApiPath2[Long, Long](DELETE, "/api/groups/{groupId}/tags/{tagId}")

  val all: List[ApiPath] = List(
    list,
    get,
    create,
    join,
    leave,
    renameGroup,
    regenerateInviteCode,
    setMemberRole,
    removeMember,
    deleteGroup,
    attachTag,
    detachTag,
  )
}

/** Shareable tag groups — classroom-style collaboration on top of the tag/word model. A group has two roles, `admin`
  * and `member`; attaching one of a caller's own tags to a group they belong to opens that tag's *content* (words,
  * marked translations) to every member, while renaming/deleting it stays the tag owner's alone — see
  * `WordService.requireEditableTag`.
  *
  * [[list]]/[[get]] sit behind `optionalUser`, the same as [[WordEndpoints.list]]/[[WordEndpoints.get]] — a visitor
  * with no session browses and views groups too. Everything else sits behind `authenticated`. [[list]]/[[get]] answer
  * the same result to every viewer regardless of membership, only [[GroupDetail.members]] and `.inviteCode` narrow by
  * the caller's own standing in that particular group.
  */
object GroupEndpoints {

  private val paths = GroupPaths

  private val noContent = HttpCodec.status(Status.NoContent)

  /** Paged/sorted/filtered the same way `AdminEndpoints.listUsers` is — see its own doc comment for why every one of
    * these is optional rather than defaulted. `q` narrows by name, a case-insensitive substring; `tag` narrows to
    * groups holding an attached tag whose name contains it, also case-insensitive.
    */
  private val pageQuery     = HttpCodec.query[Int]("page").optional
  private val pageSizeQuery = HttpCodec.query[Int]("pageSize").optional
  private val sortQuery     = HttpCodec.query[String]("sort").optional
  private val dirQuery      = HttpCodec.query[String]("dir").optional
  private val searchQuery   = HttpCodec.query[String]("q").optional
  private val tagQuery      = HttpCodec.query[String]("tag").optional

  /** One page of groups, with the caller's own role in each (`None` for one they haven't joined) — what the browse/join
    * page is built from.
    */
  val list = {
    Endpoint(ApiRoutes.route0(paths.list))
      .query(pageQuery)
      .query(pageSizeQuery)
      .query(sortQuery)
      .query(dirQuery)
      .query(searchQuery)
      .query(tagQuery)
      .withCodecError
      .out[GroupPage]
      .outFailure(failure.badRequest)
  }

  /** One group's detail. `members` is empty and `inviteCode` is `None` unless the caller is themself a member (for the
    * roster) or an admin (for the code) of this particular group — see [[GroupDetail]]. Callable with no session at
    * all, the same as [[WordEndpoints.get]] — a visitor gets the same detail with no roster/invite-code/role.
    */
  val get = {
    Endpoint(ApiRoutes.route1(paths.get, PathCodec.long)).withCodecError
      .out[GroupDetail]
      .outErrors(failure.badRequest, failure.notFound)
  }

  /** Creates a group; the caller becomes its sole admin. 400 covers a blank name or one over `Group.maxNameLength`. */
  val create = {
    Endpoint(ApiRoutes.route0(paths.create))
      .in[CreateGroupRequest]
      .withCodecError
      .out[GroupDetail](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  /** Redeems an invite code, joining the caller as a plain member. Idempotent for a code whose group the caller already
    * belongs to — redeeming twice is not a conflict. 404 covers an unknown or rotated code; a caller cannot tell the
    * two apart, the same rule `AuthEndpoints.claimGuest` follows for its own code space. 429 covers the caller's own
    * `RateLimitKey.groupJoin` budget, the same reason `claimGuest` has one for guessing.
    */
  val join = {
    Endpoint(ApiRoutes.route0(paths.join))
      .in[JoinGroupRequest]
      .withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.tooManyRequests)
  }

  /** Removes the caller from the group's roster. 409 covers the caller being its last admin — a group may never be left
    * with none; promote a second admin first.
    */
  val leave = {
    Endpoint(ApiRoutes.route1(paths.leave, PathCodec.long)).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Admin-only. Renames the group. Follows [[create]]'s own rules for the name itself — 400 for blank or over
    * `Group.maxNameLength`; unlike a tag's own rename, no per-account uniqueness check, since several groups may
    * legitimately share a name.
    */
  val renameGroup = {
    Endpoint(ApiRoutes.route1(paths.renameGroup, PathCodec.long))
      .in[RenameGroupRequest]
      .withCodecError
      .out[GroupDetail]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Admin-only. Mints a fresh invite code and immediately invalidates the old one. */
  val regenerateInviteCode = {
    Endpoint(ApiRoutes.route1(paths.regenerateInviteCode, PathCodec.long)).withCodecError
      .out[InviteCodeResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Admin-only. Promotes or demotes another member. 409 covers demoting the group's last admin. */
  val setMemberRole = {
    Endpoint(ApiRoutes.route2(paths.setMemberRole, PathCodec.long, PathCodec.long))
      .in[SetMemberRoleRequest]
      .withCodecError
      .out[GroupMemberSummary]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Admin-only. Removes another member outright. 409 covers removing the group's last admin. */
  val removeMember = {
    Endpoint(ApiRoutes.route2(paths.removeMember, PathCodec.long, PathCodec.long)).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Admin-only. Deletes the group outright — every attached tag reverts to owner-only edit rights and every membership
    * row is dropped, both at the database level.
    */
  val deleteGroup = {
    Endpoint(ApiRoutes.route1(paths.deleteGroup, PathCodec.long)).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Attaches one of the caller's own tags to a group they already belong to, opening its content to every member. 403
    * covers the caller not being a member of `groupId`, or not owning `tagId`. 409 covers the tag already belonging to
    * a group (possibly this one) — detach it first.
    */
  val attachTag = {
    Endpoint(ApiRoutes.route2(paths.attachTag, PathCodec.long, PathCodec.long)).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Detaches a tag from the group, reverting it to owner-only edit rights. Callable by the tag's own owner or by any
    * admin of the group it currently belongs to — a moderation valve independent of who owns the tag.
    */
  val detachTag = {
    Endpoint(ApiRoutes.route2(paths.detachTag, PathCodec.long, PathCodec.long)).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** [[list]]/[[get]] sit behind `optionalUser`, not `authenticated` — for `DocsRoutes.publicEndpoints`. */
  val public: List[Endpoint[?, ?, ?, ?, ?]] = List(list, get)

  /** For `DocsRoutes`, which needs every description as one heterogeneous collection. */
  val all: List[Endpoint[?, ?, ?, ?, ?]] = {
    List(
      list,
      get,
      create,
      join,
      leave,
      renameGroup,
      regenerateInviteCode,
      setMemberRole,
      removeMember,
      attachTag,
      detachTag,
      deleteGroup,
    )
  }
}
