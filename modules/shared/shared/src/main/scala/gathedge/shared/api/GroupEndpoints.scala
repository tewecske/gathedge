package gathedge.shared.api

import gathedge.shared.domain.Group
import gathedge.shared.dto.{
  CreateGroupRequest,
  GroupDetail,
  GroupMemberSummary,
  InviteCodeResponse,
  JoinGroupRequest,
  RenameGroupRequest,
  SetMemberRoleRequest,
}
import zio.http.{Method, Status}
import zio.http.codec.{HttpCodec, PathCodec}
import zio.http.endpoint.Endpoint

import ApiEndpoint.{failure, outFailure, withCodecError}
import ApiSchemas.given

/** Shareable tag groups — classroom-style collaboration on top of the tag/word model. A group has two roles, `admin`
  * and `member`; attaching one of a caller's own tags to a group they belong to opens that tag's *content* (words,
  * marked translations) to every member, while renaming/deleting it stays the tag owner's alone — see
  * `WordService.requireEditableTag`.
  *
  * Every endpoint here sits behind `authenticated`, the same as [[WordEndpoints.listTags]]: a group is visible to every
  * *account*, not to the open internet — there is no visitor-facing browse the way [[WordEndpoints.list]] is.
  * [[list]]/[[get]] answer the same result to every account regardless of membership, only [[GroupDetail.members]] and
  * `.inviteCode` narrow by the caller's own standing in that particular group.
  */
object GroupEndpoints {

  private val groupId = PathCodec.long("groupId")
  private val userId  = PathCodec.long("userId")
  private val tagId   = PathCodec.long("tagId")

  private val noContent = HttpCodec.status(Status.NoContent)

  /** Every group that exists, with the caller's own role in each (`None` for one they haven't joined) — what the
    * browse/join page is built from.
    */
  val list = {
    Endpoint(Method.GET / "api" / "groups").out[List[Group]].outFailure(failure.unauthorized)
  }

  /** One group's detail. `members` is empty and `inviteCode` is `None` unless the caller is themself a member (for the
    * roster) or an admin (for the code) of this particular group — see [[GroupDetail]].
    */
  val get = {
    Endpoint(Method.GET / "api" / "groups" / groupId).withCodecError
      .out[GroupDetail]
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound)
  }

  /** Creates a group; the caller becomes its sole admin. 400 covers a blank name or one over `Group.maxNameLength`. */
  val create = {
    Endpoint(Method.POST / "api" / "groups")
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
    Endpoint(Method.POST / "api" / "groups" / "join")
      .in[JoinGroupRequest]
      .withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.tooManyRequests)
  }

  /** Removes the caller from the group's roster. 409 covers the caller being its last admin — a group may never be left
    * with none; promote a second admin first.
    */
  val leave = {
    Endpoint(Method.POST / "api" / "groups" / groupId / "leave").withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.notFound, failure.conflict)
  }

  /** Admin-only. Renames the group. Follows [[create]]'s own rules for the name itself — 400 for blank or over
    * `Group.maxNameLength`; unlike a tag's own rename, no per-account uniqueness check, since several groups may
    * legitimately share a name.
    */
  val renameGroup = {
    Endpoint(Method.PUT / "api" / "groups" / groupId)
      .in[RenameGroupRequest]
      .withCodecError
      .out[GroupDetail]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Admin-only. Mints a fresh invite code and immediately invalidates the old one. */
  val regenerateInviteCode = {
    Endpoint(Method.POST / "api" / "groups" / groupId / "invite-code" / "regenerate").withCodecError
      .out[InviteCodeResponse]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

  /** Admin-only. Promotes or demotes another member. 409 covers demoting the group's last admin. */
  val setMemberRole = {
    Endpoint(Method.PUT / "api" / "groups" / groupId / "members" / userId / "role")
      .in[SetMemberRoleRequest]
      .withCodecError
      .out[GroupMemberSummary]
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Admin-only. Removes another member outright. 409 covers removing the group's last admin. */
  val removeMember = {
    Endpoint(Method.DELETE / "api" / "groups" / groupId / "members" / userId).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Attaches one of the caller's own tags to a group they already belong to, opening its content to every member. 403
    * covers the caller not being a member of `groupId`, or not owning `tagId`. 409 covers the tag already belonging to
    * a group (possibly this one) — detach it first.
    */
  val attachTag = {
    Endpoint(Method.PUT / "api" / "groups" / groupId / "tags" / tagId).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound, failure.conflict)
  }

  /** Detaches a tag from the group, reverting it to owner-only edit rights. Callable by the tag's own owner or by any
    * admin of the group it currently belongs to — a moderation valve independent of who owns the tag.
    */
  val detachTag = {
    Endpoint(Method.DELETE / "api" / "groups" / groupId / "tags" / tagId).withCodecError
      .outCodec(noContent)
      .outErrors(failure.badRequest, failure.unauthorized, failure.forbidden, failure.notFound)
  }

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
    )
  }
}
