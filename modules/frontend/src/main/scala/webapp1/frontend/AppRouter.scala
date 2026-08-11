package webapp1.frontend

import com.raquo.waypoint._
import webapp1.frontend.i18n.CurrentLocale
import webapp1.frontend.listing.{AuditQuery, UserQuery}

sealed trait Page

object Page {
  case object SignIn                           extends Page
  case object SignUp                           extends Page
  case object Home                             extends Page
  case object Groups                           extends Page
  final case class GroupDetail(id: Long)       extends Page
  final case class GroupMembers(id: Long)      extends Page
  final case class AcceptInvite(token: String) extends Page

  /** Where a verification link lands. Public: the account it verifies usually cannot sign in yet. */
  final case class VerifyEmail(token: String) extends Page

  /** Shown after a signup that did not sign the user in, and wherever a fresh link needs asking for. */
  case object CheckInbox extends Page
  case object Settings   extends Page

  /** The user list. It carries its whole listing state, so a filtered, sorted, paged view has an address somebody can
    * bookmark or send on — see [[webapp1.frontend.listing.UserQuery]] and the two routes below.
    */
  final case class Admin(query: UserQuery = UserQuery.default) extends Page

  final case class AdminUserDetail(id: Long) extends Page

  /** The two administrator screens that are not about one account: the audit trail, and the deployment itself. */
  final case class AdminAudit(query: AuditQuery = AuditQuery.default) extends Page

  case object AdminSystem extends Page
  case object Forbidden   extends Page
  case object NotFound    extends Page

  enum AuthGuard {

    /** Redirects an unauthenticated visitor to sign-in. */
    case RequireAuth

    /** Redirects an already-authenticated visitor to Home (sign-in/sign-up). */
    case RequireAnon

    /** Renders regardless of auth state (accept-invite, forbidden, not-found). */
    case Public
  }

  def guardFor(page: Page): AuthGuard = {
    page match {
      case SignIn | SignUp                                                      =>
        AuthGuard.RequireAnon
      case AcceptInvite(_) | VerifyEmail(_) | CheckInbox | Forbidden | NotFound =>
        AuthGuard.Public
      case _                                                                    =>
        AuthGuard.RequireAuth
    }
  }
}

object AppRouter {
  import Page._

  /** Every route is mounted under the language prefix this page load is in — `/en/groups`, `/hu/groups`.
    *
    * Waypoint's `basePath` does all of the work: it is prepended when a URL is *built* (`Route.relativeUrlForPage` is
    * `basePath + createRelativeUrl(args)`) and stripped when one is *matched*. So every internal link and every
    * `pushState` picks up the prefix with no further help, and `Page` stays exactly what it was — no locale field, no
    * new serialization tag, no change to any call site.
    *
    * The value is fixed for the lifetime of the document, which is why switching language is a full navigation to the
    * other prefix rather than something the router can do.
    */
  private val basePath = CurrentLocale.prefix

  private val signInRoute          = Route.static(SignIn, root / "sign-in", basePath)
  private val signUpRoute          = Route.static(SignUp, root / "sign-up", basePath)
  private val homeRoute            = Route.static(Home, root, basePath)
  private val groupsRoute          = Route.static(Groups, root / "groups", basePath)
  private val settingsRoute        = Route.static(Settings, root / "settings", basePath)
  private val groupDetailRoute     = Route(
    encode = (p: GroupDetail) => p.id,
    decode = (id: Long) => GroupDetail(id),
    pattern = root / "groups" / segment[Long],
    basePath = basePath,
  )
  private val groupMembersRoute    = Route(
    encode = (p: GroupMembers) => p.id,
    decode = (id: Long) => GroupMembers(id),
    pattern = root / "groups" / segment[Long] / "members",
    basePath = basePath,
  )
  private val acceptInviteRoute    = Route(
    encode = (p: AcceptInvite) => p.token,
    decode = (token: String) => AcceptInvite(token),
    pattern = root / "invitations" / segment[String],
    basePath = basePath,
  )
  private val verifyEmailRoute     = Route(
    encode = (p: VerifyEmail) => p.token,
    decode = (token: String) => VerifyEmail(token),
    pattern = root / "verify-email" / segment[String],
    basePath = basePath,
  )
  private val checkInboxRoute      = Route.static(CheckInbox, root / "check-inbox", basePath)
  private val adminUserDetailRoute = Route(
    encode = (p: AdminUserDetail) => p.id,
    decode = (id: Long) => AdminUserDetail(id),
    pattern = root / "admin" / "users" / segment[Long],
    basePath = basePath,
  )
  private val adminSystemRoute     = Route.static(AdminSystem, root / "admin" / "system", basePath)
  private val forbiddenRoute       = Route.static(Forbidden, root / "forbidden", basePath)

  /** The two listings get **two routes each**: one that carries a query string and one that is the bare path.
    *
    * A single `Route.onlyQuery` would address the unfiltered list as `/admin/users?` — url-dsl's `createUrlString`
    * joins the path and the parameters with a `?` whether or not there are any parameters to write. So the query route
    * is a *partial* one, defined in both directions only when there is something to carry, and the plain path answers
    * for the default. Order in [[router]] decides which of a pair answers, and it is the same list for building a URL
    * and for matching one, so the query route has to come first in both.
    */
  private val adminQueryRoute = Route.onlyQueryPF[Admin, UserQuery](
    matchEncode = { case page: Admin if page.query != UserQuery.default => page.query },
    decode = { case query if query != UserQuery.default => Admin(query) },
    pattern = (root / "admin" / "users") ? UserQuery.params,
    basePath = basePath,
  )

  private val adminRoute = Route.staticPartial(Admin(), root / "admin" / "users", basePath)

  private val adminAuditQueryRoute = Route.onlyQueryPF[AdminAudit, AuditQuery](
    matchEncode = { case page: AdminAudit if page.query != AuditQuery.default => page.query },
    decode = { case query if query != AuditQuery.default => AdminAudit(query) },
    pattern = (root / "admin" / "audit") ? AuditQuery.params,
    basePath = basePath,
  )

  private val adminAuditRoute = Route.staticPartial(AdminAudit(), root / "admin" / "audit", basePath)

  // All pages are derivable from the URL alone, so serialization (used only for
  // browser-history state) is just a tag — no JSON library needed.
  //
  // The two listings are the exception, and it is not optional: Waypoint restores a page from `deserializePage` of
  // the history state, *not* by matching the URL again, so a tag that dropped the query would answer the back button
  // with the filter silently gone. They reuse the same `QueryParameters` value the URL is built from, so there is one
  // encoding rather than two — and a search term containing `&` or `=` survives, which a hand-rolled tag would not.
  // `private[frontend]` rather than `private` only so `AppRouterSpec` can state the round trip; nothing outside this
  // file calls either of them.
  private[frontend] def serialize(page: Page): String = {
    page match {
      case SignIn              =>
        "SignIn"
      case SignUp              =>
        "SignUp"
      case Home                =>
        "Home"
      case Groups              =>
        "Groups"
      case Settings            =>
        "Settings"
      case GroupDetail(id)     =>
        s"GroupDetail:$id"
      case GroupMembers(id)    =>
        s"GroupMembers:$id"
      case AcceptInvite(token) =>
        s"AcceptInvite:$token"
      case VerifyEmail(token)  =>
        s"VerifyEmail:$token"
      case CheckInbox          =>
        "CheckInbox"
      case Admin(query)        =>
        "Admin:" + UserQuery.params.createParamsString(query)
      case AdminUserDetail(id) =>
        s"AdminUserDetail:$id"
      case AdminAudit(query)   =>
        "AdminAudit:" + AuditQuery.params.createParamsString(query)
      case AdminSystem         =>
        "AdminSystem"
      case Forbidden           =>
        "Forbidden"
      case NotFound            =>
        "NotFound"
    }
  }

  // A corrupt/truncated id in a history entry means the tag isn't a page we can restore —
  // fall back to NotFound rather than fabricating id 0 and letting the page 404 against the API.
  private def withId(tag: String, prefix: String)(page: Long => Page): Page = {
    tag.stripPrefix(prefix).toLongOption.map(page).getOrElse(NotFound)
  }

  private[frontend] def deserialize(tag: String): Page = {
    if (tag.startsWith("GroupMembers:")) {
      withId(tag, "GroupMembers:")(GroupMembers.apply)
    } else if (tag.startsWith("GroupDetail:")) {
      withId(tag, "GroupDetail:")(GroupDetail.apply)
    } else if (tag.startsWith("AcceptInvite:")) {
      AcceptInvite(tag.stripPrefix("AcceptInvite:"))
    } else if (tag.startsWith("VerifyEmail:")) {
      VerifyEmail(tag.stripPrefix("VerifyEmail:"))
    } else if (tag.startsWith("AdminUserDetail:")) {
      withId(tag, "AdminUserDetail:")(AdminUserDetail.apply)
    } else if (tag.startsWith("AdminAudit:")) {
      // A tag we cannot read is a history entry from an older build; the listing itself is still the right screen, so
      // fall back to its default view rather than to Not Found.
      AuditQuery.params
        .matchQueryString(tag.stripPrefix("AdminAudit:"))
        .map(query => AdminAudit(query))
        .getOrElse(AdminAudit())
    } else if (tag.startsWith("Admin:")) {
      UserQuery.params.matchQueryString(tag.stripPrefix("Admin:")).map(query => Admin(query)).getOrElse(Admin())
    } else {
      tag match {
        case "SignIn"      =>
          SignIn
        case "SignUp"      =>
          SignUp
        case "Home"        =>
          Home
        case "Groups"      =>
          Groups
        case "Settings"    =>
          Settings
        case "CheckInbox"  =>
          CheckInbox
        // The colon-less forms are what a history entry written by an older build holds.
        case "Admin"       =>
          Admin()
        case "AdminAudit"  =>
          AdminAudit()
        case "AdminSystem" =>
          AdminSystem
        case "Forbidden"   =>
          Forbidden
        case _             =>
          NotFound
      }
    }
  }

  val router: Router[Page] = {
    new Router[Page](
      routes = List(
        signInRoute,
        signUpRoute,
        homeRoute,
        groupsRoute,
        settingsRoute,
        groupDetailRoute,
        groupMembersRoute,
        acceptInviteRoute,
        verifyEmailRoute,
        checkInboxRoute,
        // Each listing's query route must precede its bare-path one; see the comment on `adminQueryRoute`.
        adminQueryRoute,
        adminRoute,
        adminUserDetailRoute,
        adminAuditQueryRoute,
        adminAuditRoute,
        adminSystemRoute,
        forbiddenRoute,
      ),
      serializePage = serialize,
      deserializePage = deserialize,
      getPageTitle = _ => "webapp1",
      routeFallback = _ => NotFound,
    )
  }
}
