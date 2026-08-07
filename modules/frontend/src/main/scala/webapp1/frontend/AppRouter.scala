package webapp1.frontend

import com.raquo.waypoint._

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
  case object CheckInbox                     extends Page
  case object Settings                       extends Page
  case object Admin                          extends Page
  final case class AdminUserDetail(id: Long) extends Page

  /** The two administrator screens that are not about one account: the audit trail, and the deployment itself. */
  case object AdminAudit  extends Page
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

  private val signInRoute          = Route.static(SignIn, root / "sign-in")
  private val signUpRoute          = Route.static(SignUp, root / "sign-up")
  private val homeRoute            = Route.static(Home, root)
  private val groupsRoute          = Route.static(Groups, root / "groups")
  private val settingsRoute        = Route.static(Settings, root / "settings")
  private val groupDetailRoute     = Route(
    encode = (p: GroupDetail) => p.id,
    decode = (id: Long) => GroupDetail(id),
    pattern = root / "groups" / segment[Long],
  )
  private val groupMembersRoute    = Route(
    encode = (p: GroupMembers) => p.id,
    decode = (id: Long) => GroupMembers(id),
    pattern = root / "groups" / segment[Long] / "members",
  )
  private val acceptInviteRoute    = Route(
    encode = (p: AcceptInvite) => p.token,
    decode = (token: String) => AcceptInvite(token),
    pattern = root / "invitations" / segment[String],
  )
  private val verifyEmailRoute     = Route(
    encode = (p: VerifyEmail) => p.token,
    decode = (token: String) => VerifyEmail(token),
    pattern = root / "verify-email" / segment[String],
  )
  private val checkInboxRoute      = Route.static(CheckInbox, root / "check-inbox")
  private val adminRoute           = Route.static(Admin, root / "admin" / "users")
  private val adminUserDetailRoute = Route(
    encode = (p: AdminUserDetail) => p.id,
    decode = (id: Long) => AdminUserDetail(id),
    pattern = root / "admin" / "users" / segment[Long],
  )
  private val adminAuditRoute      = Route.static(AdminAudit, root / "admin" / "audit")
  private val adminSystemRoute     = Route.static(AdminSystem, root / "admin" / "system")
  private val forbiddenRoute       = Route.static(Forbidden, root / "forbidden")

  // All pages are derivable from the URL alone, so serialization (used only for
  // browser-history state) is just a tag — no JSON library needed.
  private def serialize(page: Page): String = {
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
      case Admin               =>
        "Admin"
      case AdminUserDetail(id) =>
        s"AdminUserDetail:$id"
      case AdminAudit          =>
        "AdminAudit"
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

  private def deserialize(tag: String): Page = {
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
        case "Admin"       =>
          Admin
        case "AdminAudit"  =>
          AdminAudit
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
        adminRoute,
        adminUserDetailRoute,
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
