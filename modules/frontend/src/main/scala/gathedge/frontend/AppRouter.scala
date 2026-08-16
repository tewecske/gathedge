package gathedge.frontend

import com.raquo.waypoint._
import gathedge.frontend.i18n.CurrentLocale
import gathedge.frontend.listing.{AuditQuery, UserQuery, WordQuery}
import gathedge.shared.Branding

sealed trait Page

object Page {
  case object SignIn extends Page
  case object SignUp extends Page

  /** The skeleton's landing page — a placeholder a new project replaces with its own first screen. */
  case object Home extends Page

  /** Where a verification link lands. Public: the account it verifies usually cannot sign in yet. */
  final case class VerifyEmail(token: String) extends Page

  /** Shown after a signup that did not sign the user in, and wherever a fresh link needs asking for. */
  case object CheckInbox extends Page
  case object Settings   extends Page

  /** The catalog of game types. Public, like [[Home]] and [[Words]]: a shared game link should show the catalog without
    * bouncing a signed-out visitor to sign-in. Playing a game (not this page) is what mints a guest account.
    */
  case object Games extends Page

  /** Choosing a language pair and tags for a fresh vocabulary quiz. Public like [[Games]] — a shared link must render
    * for a signed-out visitor — but unlike [[Games]], its own tag fetch mints a guest on arrival: see `GameSetupPage`'s
    * doc comment for why this one screen departs from "never on a page view".
    */
  case object GameSetup extends Page

  /** Where "Forgot your password?" on the sign-in form leads. Signed-out only, like sign-in and sign-up. */
  case object ForgotPassword extends Page

  /** Where a password-reset link lands. Public for the same reason [[VerifyEmail]] is: the account it resets usually
    * has no session either.
    */
  final case class ResetPassword(token: String) extends Page

  /** The vocabulary: the shared dictionary, and the reader's own tags on it.
    *
    * Public, and the only listing in the application that is. A visitor with no session sees the same words and none of
    * the tag marks; tagging one is what mints them a guest account. It carries its whole listing state for the same
    * reason the admin listings do — `/words?lang=de&target=hu&q=hau&tag=3` is a screen worth sending to somebody.
    */
  final case class Words(query: WordQuery = WordQuery.default) extends Page

  /** One word: every translation anybody has recorded for it, and the reader's tags. Public for the same reason. */
  final case class WordDetail(id: Long) extends Page

  /** The user list. It carries its whole listing state, so a filtered, sorted, paged view has an address somebody can
    * bookmark or send on — see [[gathedge.frontend.listing.UserQuery]] and the two routes below.
    */
  final case class Admin(query: UserQuery = UserQuery.default) extends Page

  final case class AdminUserDetail(id: Long) extends Page

  /** The two administrator screens that are not about one account: the audit trail, and the deployment itself. */
  final case class AdminAudit(query: AuditQuery = AuditQuery.default) extends Page

  case object AdminSystem extends Page

  /** What routes get used, and which accounts look unusual — see `gathedge.backend.service.UsageStatsService`. No
    * listing state of its own, like [[AdminSystem]]: the window is a control on the page rather than something worth
    * bookmarking a particular value of.
    */
  case object AdminUsage extends Page
  case object Forbidden  extends Page
  case object NotFound   extends Page

  enum AuthGuard {

    /** Redirects an unauthenticated visitor to sign-in. */
    case RequireAuth

    /** Redirects an already-authenticated visitor to Home (sign-in/sign-up). */
    case RequireAnon

    /** Renders regardless of auth state (verify-email, check-inbox, forbidden, not-found). */
    case Public
  }

  def guardFor(page: Page): AuthGuard = {
    page match {
      case SignIn | SignUp | ForgotPassword                                      =>
        AuthGuard.RequireAnon
      case VerifyEmail(_) | CheckInbox | ResetPassword(_) | Forbidden | NotFound =>
        AuthGuard.Public
      // The whole point of the vocabulary is that it is usable before signing up for anything.
      case Words(_) | WordDetail(_)                                              =>
        AuthGuard.Public
      // Home is the target of the navbar's own link, always shown — it must not bounce a signed-out click back to
      // sign-in. Games is the same: a shared link has to show the catalog, not sign-in.
      case Home | Games | GameSetup                                              =>
        AuthGuard.Public
      case _                                                                     =>
        AuthGuard.RequireAuth
    }
  }
}

object AppRouter {
  import Page._

  /** Every route is mounted under the language prefix this page load is in — `/en/settings`, `/hu/settings`.
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
  private val settingsRoute        = Route.static(Settings, root / "settings", basePath)
  private val gamesRoute           = Route.static(Games, root / "games", basePath)
  private val gameSetupRoute       = Route.static(GameSetup, root / "games" / "vocabulary-quiz", basePath)
  private val verifyEmailRoute     = Route(
    encode = (p: VerifyEmail) => p.token,
    decode = (token: String) => VerifyEmail(token),
    pattern = root / "verify-email" / segment[String],
    basePath = basePath,
  )
  private val checkInboxRoute      = Route.static(CheckInbox, root / "check-inbox", basePath)
  private val forgotPasswordRoute  = Route.static(ForgotPassword, root / "forgot-password", basePath)
  private val resetPasswordRoute   = Route(
    encode = (p: ResetPassword) => p.token,
    decode = (token: String) => ResetPassword(token),
    pattern = root / "reset-password" / segment[String],
    basePath = basePath,
  )
  private val adminUserDetailRoute = Route(
    encode = (p: AdminUserDetail) => p.id,
    decode = (id: Long) => AdminUserDetail(id),
    pattern = root / "admin" / "users" / segment[Long],
    basePath = basePath,
  )
  private val adminSystemRoute     = Route.static(AdminSystem, root / "admin" / "system", basePath)
  private val adminUsageRoute      = Route.static(AdminUsage, root / "admin" / "usage", basePath)
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

  private val wordsQueryRoute = Route.onlyQueryPF[Words, WordQuery](
    matchEncode = { case page: Words if page.query != WordQuery.default => page.query },
    decode = { case query if query != WordQuery.default => Words(query) },
    pattern = (root / "words") ? WordQuery.params,
    basePath = basePath,
  )

  private val wordsRoute = Route.staticPartial(Words(), root / "words", basePath)

  private val wordDetailRoute = Route(
    encode = (p: WordDetail) => p.id,
    decode = (id: Long) => WordDetail(id),
    pattern = root / "words" / segment[Long],
    basePath = basePath,
  )

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
      case SignIn               =>
        "SignIn"
      case SignUp               =>
        "SignUp"
      case Home                 =>
        "Home"
      case Settings             =>
        "Settings"
      case Games                =>
        "Games"
      case GameSetup            =>
        "GameSetup"
      case VerifyEmail(token)   =>
        s"VerifyEmail:$token"
      case CheckInbox           =>
        "CheckInbox"
      case ForgotPassword       =>
        "ForgotPassword"
      case ResetPassword(token) =>
        s"ResetPassword:$token"
      case Admin(query)         =>
        "Admin:" + UserQuery.params.createParamsString(query)
      case AdminUserDetail(id)  =>
        s"AdminUserDetail:$id"
      case Words(query)         =>
        "Words:" + WordQuery.params.createParamsString(query)
      case WordDetail(id)       =>
        s"WordDetail:$id"
      case AdminAudit(query)    =>
        "AdminAudit:" + AuditQuery.params.createParamsString(query)
      case AdminSystem          =>
        "AdminSystem"
      case AdminUsage           =>
        "AdminUsage"
      case Forbidden            =>
        "Forbidden"
      case NotFound             =>
        "NotFound"
    }
  }

  // A corrupt/truncated id in a history entry means the tag isn't a page we can restore —
  // fall back to NotFound rather than fabricating id 0 and letting the page 404 against the API.
  private def withId(tag: String, prefix: String)(page: Long => Page): Page = {
    tag.stripPrefix(prefix).toLongOption.map(page).getOrElse(NotFound)
  }

  private[frontend] def deserialize(tag: String): Page = {
    if (tag.startsWith("VerifyEmail:")) {
      VerifyEmail(tag.stripPrefix("VerifyEmail:"))
    } else if (tag.startsWith("ResetPassword:")) {
      ResetPassword(tag.stripPrefix("ResetPassword:"))
    } else if (tag.startsWith("WordDetail:")) {
      withId(tag, "WordDetail:")(WordDetail.apply)
    } else if (tag.startsWith("Words:")) {
      WordQuery.params.matchQueryString(tag.stripPrefix("Words:")).map(query => Words(query)).getOrElse(Words())
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
        case "SignIn"         =>
          SignIn
        case "SignUp"         =>
          SignUp
        case "Home"           =>
          Home
        case "Settings"       =>
          Settings
        case "Games"          =>
          Games
        case "GameSetup"      =>
          GameSetup
        case "CheckInbox"     =>
          CheckInbox
        case "ForgotPassword" =>
          ForgotPassword
        // The colon-less forms are what a history entry written by an older build holds.
        case "Admin"          =>
          Admin()
        case "Words"          =>
          Words()
        case "AdminAudit"     =>
          AdminAudit()
        case "AdminSystem"    =>
          AdminSystem
        case "AdminUsage"     =>
          AdminUsage
        case "Forbidden"      =>
          Forbidden
        case _                =>
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
        settingsRoute,
        gamesRoute,
        gameSetupRoute,
        verifyEmailRoute,
        checkInboxRoute,
        forgotPasswordRoute,
        resetPasswordRoute,
        // Each listing's query route must precede its bare-path one; see the comment on `adminQueryRoute`.
        wordsQueryRoute,
        wordsRoute,
        wordDetailRoute,
        adminQueryRoute,
        adminRoute,
        adminUserDetailRoute,
        adminAuditQueryRoute,
        adminAuditRoute,
        adminSystemRoute,
        adminUsageRoute,
        forbiddenRoute,
      ),
      serializePage = serialize,
      deserializePage = deserialize,
      getPageTitle = _ => Branding.appName,
      routeFallback = _ => NotFound,
    )
  }
}
