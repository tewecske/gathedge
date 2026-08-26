package gathedge.frontend

import com.raquo.waypoint._
import gathedge.frontend.i18n.CurrentLocale
import gathedge.frontend.listing.{AuditQuery, GamePlayQuery, UserQuery, WordQuery}
import gathedge.shared.Branding

sealed trait Page

object Page {
  case object SignIn extends Page
  case object SignUp extends Page

  /** Where a verification link lands. Public: the account it verifies usually cannot sign in yet. */
  final case class VerifyEmail(token: String) extends Page

  /** Shown after a signup that did not sign the user in, and wherever a fresh link needs asking for. */
  case object CheckInbox extends Page
  case object Settings   extends Page

  /** The catalog of game types. Public, like [[Words]]: a shared game link should show the catalog without bouncing a
    * signed-out visitor to sign-in. Playing a game (not this page) is what mints a guest account.
    */
  case object Games extends Page

  /** Choosing a language pair and tags for a fresh vocabulary quiz. Public like [[Games]] — a shared link must render
    * for a signed-out visitor — but unlike [[Games]], its own tag fetch mints a guest on arrival: see `GameSetupPage`'s
    * doc comment for why this one screen departs from "never on a page view".
    */
  case object GameSetup extends Page

  /** The signed-in owner's own games: name, tags, language pair, and how many times each was played. Unlike
    * [[Games]]/[[GameSetup]]/[[GameInstance]], there is no shared link to keep public — it is a personal listing, so it
    * requires auth like the rest of the account-scoped pages.
    */
  case object MyGames extends Page

  /** One quiz, playable from its shared link: `/g/{slug}`. Public for the same reason [[GameSetup]] is public and
    * [[WordDetail]] is — a shared link has to render for a signed-out visitor — but nothing here mints a guest on
    * arrival, unlike `GameSetup`: reading the game's name and tags is not a write. It is starting a play, the first
    * action the page offers, that goes through the guest detour, in `GameInstancePage`.
    */
  final case class GameInstance(slug: String) extends Page

  /** The play loop for one attempt at a game: prompt, answer, next, until `playId` finishes. Reached only by navigating
    * here right after `startPlay` succeeds (the picker on [[GameInstance]], or a "Play again" shortcut via
    * `GameReplay.start`) — never rendered from a bare URL with nothing else known about it, since there is no way to
    * recover a play's word count from just its id (`GamePlayPage`'s doc comment). Public for the same reason
    * [[GameInstance]] is: a bookmarked/shared/refreshed link must still render, even with no matching in-memory
    * hand-off — it just bounces straight back to the picker in that case, rather than to sign-in.
    */
  final case class GamePlay(slug: String, playId: Long) extends Page

  /** A tracked game's owner-facing results listing: who played `slug` and how they scored. Owner-only (the default
    * `AuthGuard.RequireAuth` covers it — see `guardFor`), unlike [[GameInstance]]: a shared link must stay public, but
    * a game's play history is not something a shared link should leak. It carries its whole listing state, the same
    * reason [[Admin]]/[[AdminAudit]]/[[Words]] do — see [[gathedge.frontend.listing.GamePlayQuery]] and the route
    * below.
    */
  final case class GameResults(slug: String, query: GamePlayQuery = GamePlayQuery.default) extends Page

  /** The signed-in caller's own play history across every game — the foundation [[SharedPlayerHistory]] and the admin
    * games tab both reuse, addressed by a different account id and a different authorization check. Auth-only like
    * [[MyGames]], for the same reason: personal, no shared link.
    */
  case object MyPlays extends Page

  /** Progress sharing: the caller's own share code, who it is shared with, redeeming somebody else's code, and the list
    * of accounts that have shared with the caller. Auth-only: sharing is between two signed-in accounts.
    */
  case object SharedProgress extends Page

  /** One sharer's play history, for a viewer that sharer has granted access to — reuses [[MyPlays]]'s table, filtered
    * server-side to `trackResults = true` games and gated by `ProgressShareService.requireShareAccess`.
    */
  final case class SharedPlayerHistory(sharerUserId: Long) extends Page

  /** Where "Forgot your password?" on the sign-in form leads. Signed-out only, like sign-in and sign-up. */
  case object ForgotPassword extends Page

  /** Where a password-reset link lands. Public for the same reason [[VerifyEmail]] is: the account it resets usually
    * has no session either.
    */
  final case class ResetPassword(token: String) extends Page

  /** What the site is for, who runs it, and how it is licensed. Public like [[Games]]: a visitor who lands on the
    * sign-in page still needs a way to reach it, without being bounced back to sign-in.
    */
  case object About extends Page

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

  /** The `word_forms` fan-out diagnostics — see `gathedge.shared.dto.WordFormAnomaly`. No listing state, same as
    * [[AdminSystem]]/[[AdminUsage]]: it is a report with a delete action, not something worth bookmarking a filtered
    * view of.
    */
  case object AdminWordForms extends Page

  /** Every rate-limiter key currently holding failures — who is blocked or approaching it, and for which action. No
    * listing state, same as [[AdminSystem]]/[[AdminUsage]]/[[AdminWordForms]]: it is a live snapshot, not a filtered
    * view worth bookmarking.
    */
  case object AdminRateLimits extends Page

  /** Browsing/creating/joining classroom-style tag groups. Auth-only, like [[SharedProgress]]: collaborating on a group
    * is between signed-in accounts, and `GroupEndpoints.list`/`.get` themselves need a session, unlike
    * [[Words]]/[[WordDetail]]'s public pair.
    */
  case object Groups extends Page

  /** One group's roster (visible only to its own members), invite code (admins only), and attached tags. */
  final case class GroupDetail(id: Long) extends Page

  /** Where a group's invite link lands — `/groups/join/{code}`, the URL [[GroupDetailPage]]'s share row builds and
    * encodes as a QR code, the same shape `GameInstance`'s shared link is. Unlike that one, this page is not itself
    * public: every `GroupEndpoints` call needs a session (see `GroupEndpoints`'s doc comment), so a signed-out visitor
    * following the link bounces to sign-in first, same as every other `Groups` screen — there is no guest detour to
    * copy from `GameInstancePage`, since a group has no meaning for an account with no identity of its own.
    */
  final case class GroupJoin(code: String) extends Page

  /** A standalone read-only view of one tag's words and marked translations — the "tag view" `TagWordsList` was built
    * for game setup, reused here without the game-creation flow around it. Backed by the same session-only
    * `GameEndpoints.setupWords` a game's own setup screen uses, so this is auth-only too, not public like
    * [[WordDetail]].
    */
  final case class TagDetail(id: Long) extends Page

  case object Forbidden extends Page
  case object NotFound  extends Page

  enum AuthGuard {

    /** Redirects an unauthenticated visitor to sign-in. */
    case RequireAuth

    /** Redirects an already-authenticated visitor to Games (sign-in/sign-up). */
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
      // Games is the target of the navbar's own link, always shown — it must not bounce a signed-out click back to
      // sign-in. A shared link has to show the catalog, not sign-in.
      case Games | GameSetup | GameInstance(_) | GamePlay(_, _) | About          =>
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

  private val signInRoute              = Route.static(SignIn, root / "sign-in", basePath)
  private val signUpRoute              = Route.static(SignUp, root / "sign-up", basePath)
  private val aboutRoute               = Route.static(About, root / "about", basePath)
  private val settingsRoute            = Route.static(Settings, root / "settings", basePath)
  private val gamesRoute               = Route.static(Games, root, basePath)
  private val gameSetupRoute           = Route.static(GameSetup, root / "games" / "vocabulary-quiz", basePath)
  private val myGamesRoute             = Route.static(MyGames, root / "games" / "mine", basePath)
  private val myPlaysRoute             = Route.static(MyPlays, root / "games" / "history", basePath)
  private val sharedProgressRoute      = Route.static(SharedProgress, root / "games" / "shared", basePath)
  private val sharedPlayerHistoryRoute = Route(
    encode = (p: SharedPlayerHistory) => p.sharerUserId,
    decode = (id: Long) => SharedPlayerHistory(id),
    pattern = root / "games" / "shared" / segment[Long],
    basePath = basePath,
  )
  private val gameInstanceRoute        = Route(
    encode = (p: GameInstance) => p.slug,
    decode = (slug: String) => GameInstance(slug),
    pattern = root / "g" / segment[String],
    basePath = basePath,
  )
  private val gamePlayRoute            = Route(
    encode = (p: GamePlay) => (p.slug, p.playId),
    decode = (args: (String, Long)) => GamePlay(args._1, args._2),
    pattern = root / "g" / segment[String] / "play" / segment[Long],
    basePath = basePath,
  )

  /** Unlike the other listings, this one needs a path segment *and* a query — `Route.onlyQueryPF`'s "two routes, query
    * first" trick (see `adminQueryRoute`'s doc comment) only works for a fully static path, so this uses `withQuery`
    * instead, the general path-plus-query combinator. One consequence: the unfiltered URL may carry a trailing `?` (the
    * same cosmetic wart `adminQueryRoute`'s comment warns about for `onlyQuery`) since there is no bare-path fallback
    * route to prefer instead — acceptable here, since this is an owner-only diagnostic page, not one meant to be
    * hand-typed or shared.
    */
  private val gameResultsRoute     = Route.withQuery[GameResults, String, GamePlayQuery](
    encode = (p: GameResults) => PatternArgs(p.slug, p.query),
    decode = (args: PatternArgs[String, GamePlayQuery]) => GameResults(args.path, args.params),
    pattern = (root / "games" / segment[String] / "results") ? GamePlayQuery.params,
    basePath = basePath,
  )
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
  private val adminWordFormsRoute  = Route.static(AdminWordForms, root / "admin" / "word-forms", basePath)
  private val adminRateLimitsRoute = Route.static(AdminRateLimits, root / "admin" / "rate-limits", basePath)
  private val forbiddenRoute       = Route.static(Forbidden, root / "forbidden", basePath)

  private val groupsRoute      = Route.static(Groups, root / "groups", basePath)
  private val groupDetailRoute = Route(
    encode = (p: GroupDetail) => p.id,
    decode = (id: Long) => GroupDetail(id),
    pattern = root / "groups" / segment[Long],
    basePath = basePath,
  )
  private val groupJoinRoute   = Route(
    encode = (p: GroupJoin) => p.code,
    decode = (code: String) => GroupJoin(code),
    pattern = root / "groups" / "join" / segment[String],
    basePath = basePath,
  )
  private val tagDetailRoute   = Route(
    encode = (p: TagDetail) => p.id,
    decode = (id: Long) => TagDetail(id),
    pattern = root / "tags" / segment[Long],
    basePath = basePath,
  )

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
      case SignIn                   =>
        "SignIn"
      case SignUp                   =>
        "SignUp"
      case About                    =>
        "About"
      case Settings                 =>
        "Settings"
      case Games                    =>
        "Games"
      case GameSetup                =>
        "GameSetup"
      case MyGames                  =>
        "MyGames"
      case MyPlays                  =>
        "MyPlays"
      case SharedProgress           =>
        "SharedProgress"
      case SharedPlayerHistory(id)  =>
        s"SharedPlayerHistory:$id"
      case GameInstance(slug)       =>
        s"GameInstance:$slug"
      case GamePlay(slug, playId)   =>
        s"GamePlay:$slug:$playId"
      case GameResults(slug, query) =>
        s"GameResults:$slug:" + GamePlayQuery.params.createParamsString(query)
      case VerifyEmail(token)       =>
        s"VerifyEmail:$token"
      case CheckInbox               =>
        "CheckInbox"
      case ForgotPassword           =>
        "ForgotPassword"
      case ResetPassword(token)     =>
        s"ResetPassword:$token"
      case Admin(query)             =>
        "Admin:" + UserQuery.params.createParamsString(query)
      case AdminUserDetail(id)      =>
        s"AdminUserDetail:$id"
      case Words(query)             =>
        "Words:" + WordQuery.params.createParamsString(query)
      case WordDetail(id)           =>
        s"WordDetail:$id"
      case AdminAudit(query)        =>
        "AdminAudit:" + AuditQuery.params.createParamsString(query)
      case AdminSystem              =>
        "AdminSystem"
      case AdminUsage               =>
        "AdminUsage"
      case AdminWordForms           =>
        "AdminWordForms"
      case AdminRateLimits          =>
        "AdminRateLimits"
      case Groups                   =>
        "Groups"
      case GroupDetail(id)          =>
        s"GroupDetail:$id"
      case GroupJoin(code)          =>
        s"GroupJoin:$code"
      case TagDetail(id)            =>
        s"TagDetail:$id"
      case Forbidden                =>
        "Forbidden"
      case NotFound                 =>
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
    } else if (tag.startsWith("GameInstance:")) {
      GameInstance(tag.stripPrefix("GameInstance:"))
    } else if (tag.startsWith("GamePlay:")) {
      // A corrupt/truncated tag means there is no play id to resume with — fall back to `NotFound`, same convention
      // `withId` uses for every other id-keyed tag.
      val rest = tag.stripPrefix("GamePlay:")
      val sep  = rest.lastIndexOf(':')
      if (sep < 0) {
        NotFound
      } else {
        rest.substring(sep + 1).toLongOption.map(playId => GamePlay(rest.substring(0, sep), playId)).getOrElse(NotFound)
      }
    } else if (tag.startsWith("GameResults:")) {
      // A tag we cannot read is a history entry from an older build; the game's own page is still the right
      // fallback, the same reasoning `AdminAudit`'s fallback below applies to its own listing.
      val rest = tag.stripPrefix("GameResults:")
      val sep  = rest.indexOf(':')
      if (sep < 0) {
        GameResults(rest)
      } else {
        val slug = rest.substring(0, sep)
        GamePlayQuery.params
          .matchQueryString(rest.substring(sep + 1))
          .map(query => GameResults(slug, query))
          .getOrElse(GameResults(slug))
      }
    } else if (tag.startsWith("SharedPlayerHistory:")) {
      withId(tag, "SharedPlayerHistory:")(SharedPlayerHistory.apply)
    } else if (tag.startsWith("ResetPassword:")) {
      ResetPassword(tag.stripPrefix("ResetPassword:"))
    } else if (tag.startsWith("WordDetail:")) {
      withId(tag, "WordDetail:")(WordDetail.apply)
    } else if (tag.startsWith("Words:")) {
      WordQuery.params.matchQueryString(tag.stripPrefix("Words:")).map(query => Words(query)).getOrElse(Words())
    } else if (tag.startsWith("AdminUserDetail:")) {
      withId(tag, "AdminUserDetail:")(AdminUserDetail.apply)
    } else if (tag.startsWith("GroupDetail:")) {
      withId(tag, "GroupDetail:")(GroupDetail.apply)
    } else if (tag.startsWith("GroupJoin:")) {
      GroupJoin(tag.stripPrefix("GroupJoin:"))
    } else if (tag.startsWith("TagDetail:")) {
      withId(tag, "TagDetail:")(TagDetail.apply)
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
        case "SignIn"          =>
          SignIn
        case "SignUp"          =>
          SignUp
        case "About"           =>
          About
        case "Settings"        =>
          Settings
        case "Games"           =>
          Games
        case "GameSetup"       =>
          GameSetup
        case "MyGames"         =>
          MyGames
        case "MyPlays"         =>
          MyPlays
        case "SharedProgress"  =>
          SharedProgress
        case "CheckInbox"      =>
          CheckInbox
        case "ForgotPassword"  =>
          ForgotPassword
        // The colon-less forms are what a history entry written by an older build holds.
        case "Admin"           =>
          Admin()
        case "Words"           =>
          Words()
        case "AdminAudit"      =>
          AdminAudit()
        case "AdminSystem"     =>
          AdminSystem
        case "AdminUsage"      =>
          AdminUsage
        case "AdminWordForms"  =>
          AdminWordForms
        case "AdminRateLimits" =>
          AdminRateLimits
        case "Groups"          =>
          Groups
        case "Forbidden"       =>
          Forbidden
        case _                 =>
          NotFound
      }
    }
  }

  val router: Router[Page] = {
    new Router[Page](
      routes = List(
        signInRoute,
        signUpRoute,
        aboutRoute,
        settingsRoute,
        gamesRoute,
        gameSetupRoute,
        myGamesRoute,
        myPlaysRoute,
        sharedProgressRoute,
        sharedPlayerHistoryRoute,
        gameInstanceRoute,
        gamePlayRoute,
        gameResultsRoute,
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
        adminWordFormsRoute,
        adminRateLimitsRoute,
        groupsRoute,
        groupJoinRoute,
        groupDetailRoute,
        tagDetailRoute,
        forbiddenRoute,
      ),
      serializePage = serialize,
      deserializePage = deserialize,
      getPageTitle = _ => Branding.appName,
      routeFallback = _ => NotFound,
    )
  }
}
