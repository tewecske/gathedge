package webapp1.frontend

import com.raquo.waypoint._

sealed trait Page

object Page {
  case object SignIn extends Page
  case object SignUp extends Page
  case object Home extends Page
  case object Groups extends Page
  final case class GroupDetail(id: Long) extends Page
  final case class AcceptInvite(token: String) extends Page
  case object Admin extends Page
  final case class AdminUserDetail(id: Long) extends Page
  case object Forbidden extends Page
  case object NotFound extends Page

  enum AuthGuard {
    /** Redirects an unauthenticated visitor to sign-in. */
    case RequireAuth
    /** Redirects an already-authenticated visitor to Home (sign-in/sign-up). */
    case RequireAnon
    /** Renders regardless of auth state (accept-invite, forbidden, not-found). */
    case Public
  }

  def guardFor(page: Page): AuthGuard = page match {
    case SignIn | SignUp                        => AuthGuard.RequireAnon
    case AcceptInvite(_) | Forbidden | NotFound => AuthGuard.Public
    case _                                       => AuthGuard.RequireAuth
  }
}

object AppRouter {
  import Page._

  private val signInRoute = Route.static(SignIn, root / "sign-in")
  private val signUpRoute = Route.static(SignUp, root / "sign-up")
  private val homeRoute   = Route.static(Home, root)
  private val groupsRoute = Route.static(Groups, root / "groups")
  private val groupDetailRoute = Route(
    encode = (p: GroupDetail) => p.id,
    decode = (id: Long) => GroupDetail(id),
    pattern = root / "groups" / segment[Long],
  )
  private val acceptInviteRoute = Route(
    encode = (p: AcceptInvite) => p.token,
    decode = (token: String) => AcceptInvite(token),
    pattern = root / "invitations" / segment[String],
  )
  private val adminRoute = Route.static(Admin, root / "admin" / "users")
  private val adminUserDetailRoute = Route(
    encode = (p: AdminUserDetail) => p.id,
    decode = (id: Long) => AdminUserDetail(id),
    pattern = root / "admin" / "users" / segment[Long],
  )
  private val forbiddenRoute = Route.static(Forbidden, root / "forbidden")

  // All pages are derivable from the URL alone, so serialization (used only for
  // browser-history state) is just a tag — no JSON library needed.
  private def serialize(page: Page): String = page match {
    case SignIn              => "SignIn"
    case SignUp              => "SignUp"
    case Home                => "Home"
    case Groups               => "Groups"
    case GroupDetail(id)      => s"GroupDetail:$id"
    case AcceptInvite(token)  => s"AcceptInvite:$token"
    case Admin                => "Admin"
    case AdminUserDetail(id)  => s"AdminUserDetail:$id"
    case Forbidden            => "Forbidden"
    case NotFound             => "NotFound"
  }

  private def deserialize(tag: String): Page = {
    if (tag.startsWith("GroupDetail:")) {
      GroupDetail(tag.stripPrefix("GroupDetail:").toLongOption.getOrElse(0L))
    } else if (tag.startsWith("AcceptInvite:")) {
      AcceptInvite(tag.stripPrefix("AcceptInvite:"))
    } else if (tag.startsWith("AdminUserDetail:")) {
      AdminUserDetail(tag.stripPrefix("AdminUserDetail:").toLongOption.getOrElse(0L))
    } else {
      tag match {
        case "SignIn"    => SignIn
        case "SignUp"    => SignUp
        case "Home"      => Home
        case "Groups"    => Groups
        case "Admin"     => Admin
        case "Forbidden" => Forbidden
        case _           => NotFound
      }
    }
  }

  val router: Router[Page] = new Router[Page](
    routes = List(
      signInRoute,
      signUpRoute,
      homeRoute,
      groupsRoute,
      groupDetailRoute,
      acceptInviteRoute,
      adminRoute,
      adminUserDetailRoute,
      forbiddenRoute,
    ),
    serializePage = serialize,
    deserializePage = deserialize,
    getPageTitle = _ => "webapp1",
    routeFallback = _ => NotFound,
  )
}
