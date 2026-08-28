package gathedge.frontend

import gathedge.frontend.components.SortHeader
import gathedge.frontend.i18n.CurrentLocale
import gathedge.frontend.listing.{AuditQuery, GamePlayQuery, MyPlayQuery, UserQuery, WordQuery}
import gathedge.shared.domain.Locale.urlPrefix
import gathedge.shared.domain.{PartOfSpeech, WordLanguage}
import gathedge.shared.dto.{GamePlaySort, Paging, UserSort, WordSort}
import zio.test._

/** That every route carries the language prefix.
  *
  * This is the mechanism the whole frontend side of i18n rests on, and it is invisible at the call site: no page names
  * a URL, so if `basePath` were dropped from a route, every link to it would build without the prefix, match no route
  * on arrival, and land the visitor on `NotFoundPage`. Nothing would fail to compile.
  *
  * jsdom serves the specs from `/`, so `CurrentLocale` falls back to the default locale — the assertions are written
  * against its prefix rather than a hard-coded `/en` so they still state the rule if that default ever changes.
  */
object AppRouterSpec extends ZIOSpecDefault {

  private val prefix = CurrentLocale.value.urlPrefix

  def spec = {
    suite("AppRouter")(
      test("a static route builds its URL under the language prefix") {
        assertTrue(
          AppRouter.router.relativeUrlForPage(Page.SignIn) == s"$prefix/sign-in",
          AppRouter.router.relativeUrlForPage(Page.CheckInbox) == s"$prefix/check-inbox",
          AppRouter.router.relativeUrlForPage(Page.Settings) == s"$prefix/settings",
          AppRouter.router.relativeUrlForPage(Page.AdminSystem) == s"$prefix/admin/system",
          AppRouter.router.relativeUrlForPage(Page.AdminUsage) == s"$prefix/admin/usage",
        )
      },
      test("so does a route with path parameters") {
        assertTrue(
          AppRouter.router.relativeUrlForPage(Page.AdminUserDetail(3)) == s"$prefix/admin/users/3",
          AppRouter.router.relativeUrlForPage(Page.VerifyEmail("tok")) == s"$prefix/verify-email/tok",
          AppRouter.router.relativeUrlForPage(Page.ResetPassword("tok")) == s"$prefix/reset-password/tok",
        )
      },
      // Two dynamic segments chained (`/g/{slug}/play/{playId}`), unlike `GameInstance`'s single one — the play loop's
      // own route, reached only right after `startPlay` succeeds. See `Page.GamePlay`'s doc comment.
      test("a play's own route carries both its slug and its play id") {
        val page = Page.GamePlay("brave-otter", 42L)
        assertTrue(
          AppRouter.router.relativeUrlForPage(page) == s"$prefix/g/brave-otter/play/42",
          AppRouter.router.pageForRelativeUrl(s"$prefix/g/brave-otter/play/42").contains(page),
          AppRouter.deserialize(AppRouter.serialize(page)) == page,
        )
      },
      test("the forgot-password route builds under the language prefix too") {
        assertTrue(AppRouter.router.relativeUrlForPage(Page.ForgotPassword) == s"$prefix/forgot-password")
      },
      // The games route is the one whose un-prefixed form is bare `/`, so it is the one where a
      // missing prefix would be least obvious.
      test("the games route is the prefix itself") {
        assertTrue(AppRouter.router.relativeUrlForPage(Page.Games) == s"$prefix/")
      },
      // The verification link in transactional email is built server-side, by string concatenation, against this
      // same pattern. If the two ever disagree, a link that arrives in someone's inbox lands on NotFoundPage — and no
      // test on either side alone sees it. Any further link the server mails belongs here too.
      test("the email links the server builds match the routes that receive them") {
        assertTrue(AppRouter.router.relativeUrlForPage(Page.VerifyEmail("abc")) == s"$prefix/verify-email/abc")
      },
      // The whole point of the listing routes: the state that decides which rows are on screen is in the address, so
      // the address can be bookmarked and sent on. A round trip is the statement worth making — an assertion on the
      // exact string would also be asserting the order a `Map` happens to iterate in.
      test("a listing's paging, ordering and search survive a round trip through its URL") {
        val users = Page.Admin(UserQuery(page = 3, sort = SortHeader.Sort.descending(UserSort.email), search = "bob"))
        val audit = Page.AdminAudit(AuditQuery(pageSize = 50, action = Some("user.create"), actorId = Some(7L)))

        val usersUrl = AppRouter.router.relativeUrlForPage(users)
        val auditUrl = AppRouter.router.relativeUrlForPage(audit)

        assertTrue(
          usersUrl.startsWith(s"$prefix/admin/users?"),
          // One-based: the number in the address is the number on the button.
          usersUrl.contains("page=3"),
          usersUrl.contains(s"sort=${UserSort.email}"),
          usersUrl.contains("dir=desc"),
          usersUrl.contains("q=bob"),
          AppRouter.router.pageForRelativeUrl(usersUrl).contains(users),
          auditUrl.startsWith(s"$prefix/admin/audit?"),
          AppRouter.router.pageForRelativeUrl(auditUrl).contains(audit),
        )
      },
      // A parameter is written only when the reader chose something other than the default, so the plain listing keeps
      // a clean address — and the bare path still has to arrive as the same page. The first page is a default, so it
      // writes no `page` at all.
      test("the default listing is the bare path, with no query string") {
        assertTrue(
          AppRouter.router.relativeUrlForPage(Page.Admin()) == s"$prefix/admin/users",
          AppRouter.router.relativeUrlForPage(Page.AdminAudit()) == s"$prefix/admin/audit",
          AppRouter.router.relativeUrlForPage(Page.Admin(UserQuery(page = Paging.firstPage))) ==
            s"$prefix/admin/users",
          AppRouter.router.pageForRelativeUrl(s"$prefix/admin/users").contains(Page.Admin()),
          AppRouter.router.pageForRelativeUrl(s"$prefix/admin/audit").contains(Page.AdminAudit()),
          // The second page, spelled the way a reader would guess it.
          AppRouter.router.pageForRelativeUrl(s"$prefix/admin/users?page=2").contains(Page.Admin(UserQuery(page = 2))),
        )
      },
      // A URL is hand-editable and `pageSize` reaches the SQL `LIMIT`, so the bound is applied on the way in. An
      // unknown sort column is dropped rather than refused: the server would order by its own default anyway, and a
      // sort in the address that no heading marks is worse than none.
      test("a hand-edited listing URL is bounded rather than refused") {
        assertTrue(
          AppRouter.router
            .pageForRelativeUrl(s"$prefix/admin/users?size=100000")
            .contains(Page.Admin(UserQuery(pageSize = Paging.maxPageSize))),
          AppRouter.router.pageForRelativeUrl(s"$prefix/admin/users?page=-3").contains(Page.Admin()),
          // There is no page zero to show, so `page=0` is the first page rather than a Not Found.
          AppRouter.router.pageForRelativeUrl(s"$prefix/admin/users?page=0").contains(Page.Admin()),
          AppRouter.router.pageForRelativeUrl(s"$prefix/admin/users?sort=shoe-size&dir=desc").contains(Page.Admin()),
        )
      },
      // The vocabulary listing carries two things the admin ones do not: which language is being read and which one
      // the translations are in. They are in the address because "German into Hungarian" is a screen worth sending to
      // somebody, not a preference hidden in a menu.
      test("the vocabulary's languages, filters and search survive a round trip through its URL") {
        val page = Page.Words(
          WordQuery(
            page = 2,
            sort = SortHeader.Sort.ascending(WordSort.text),
            search = "hau",
            language = WordLanguage.De,
            target = WordLanguage.En,
            partOfSpeech = Some(PartOfSpeech.Noun),
            tagId = Some(4L),
            mine = true,
          )
        )
        val url  = AppRouter.router.relativeUrlForPage(page)

        assertTrue(
          url.startsWith(s"$prefix/words?"),
          url.contains("target=en"),
          url.contains("pos=noun"),
          url.contains("tag=4"),
          url.contains("mine=true"),
          // The browsed language is the default here, so it writes no parameter at all.
          !url.contains("lang="),
          AppRouter.router.pageForRelativeUrl(url).contains(page),
        )
      },
      test("the default vocabulary listing is the bare path, and its detail page takes an id") {
        assertTrue(
          AppRouter.router.relativeUrlForPage(Page.Words()) == s"$prefix/words",
          AppRouter.router.relativeUrlForPage(Page.WordDetail(12)) == s"$prefix/words/12",
          AppRouter.router.pageForRelativeUrl(s"$prefix/words").contains(Page.Words()),
          AppRouter.router.pageForRelativeUrl(s"$prefix/words/12").contains(Page.WordDetail(12)),
          // A language nobody has heard of reads as the default rather than as Not Found: a stale link should still
          // open a list of words.
          AppRouter.router.pageForRelativeUrl(s"$prefix/words?lang=xx").contains(Page.Words()),
          AppRouter.router.pageForRelativeUrl(s"$prefix/words?tag=nonsense").contains(Page.Words()),
        )
      },
      // Waypoint restores a page from the history state, not by matching the URL again, so a tag that dropped the
      // query would answer the back button with the filter silently gone.
      test("the history tag carries the listing state too") {
        val page    = Page.Admin(UserQuery(page = 2, search = "a&b=c"))
        val words   = Page.Words(WordQuery(search = "haus", target = WordLanguage.En, tagId = Some(9L)))
        val results = Page.GameResults(
          "brave-otter",
          GamePlayQuery(page = 2, sort = SortHeader.Sort.descending(GamePlaySort.score), search = "alice"),
        )
        val history = Page.MyPlays(
          MyPlayQuery(page = 3, sort = SortHeader.Sort.descending(GamePlaySort.startedAt), search = "quiz")
        )

        assertTrue(
          AppRouter.deserialize(AppRouter.serialize(page)) == page,
          AppRouter.deserialize(AppRouter.serialize(words)) == words,
          AppRouter.deserialize(AppRouter.serialize(results)) == results,
          AppRouter.deserialize(AppRouter.serialize(history)) == history,
        )
      },
      test("the cross-game history is the bare path by default and carries its query when filtered") {
        val filtered = Page.MyPlays(
          MyPlayQuery(page = 2, sort = SortHeader.Sort.ascending(GamePlaySort.score), search = "otter")
        )
        val url      = AppRouter.router.relativeUrlForPage(filtered)

        assertTrue(
          AppRouter.router.relativeUrlForPage(Page.MyPlays()) == s"$prefix/games/history",
          AppRouter.router.pageForRelativeUrl(s"$prefix/games/history").contains(Page.MyPlays()),
          url.startsWith(s"$prefix/games/history?"),
          url.contains("page=2"),
          url.contains(s"sort=${GamePlaySort.score}"),
          url.contains("q=otter"),
          AppRouter.router.pageForRelativeUrl(url).contains(filtered),
          // A hand-edited unknown column is dropped rather than refused.
          AppRouter.router.pageForRelativeUrl(s"$prefix/games/history?sort=nonsense").contains(Page.MyPlays()),
        )
      },
      // Unlike the other listings, this one needs a path segment (the game's slug) *and* a query — see
      // `AppRouter.gameResultsRoute`'s doc comment on why it uses `Route.withQuery` instead of the "two routes, query
      // first" trick the others use.
      test("a game's results listing carries both its slug and its query through the URL") {
        val filtered = Page.GameResults(
          "brave-otter",
          GamePlayQuery(page = 2, sort = SortHeader.Sort.descending(GamePlaySort.score), search = "alice"),
        )
        val url      = AppRouter.router.relativeUrlForPage(filtered)

        assertTrue(
          url.startsWith(s"$prefix/games/brave-otter/results?"),
          url.contains("page=2"),
          url.contains(s"sort=${GamePlaySort.score}"),
          url.contains("dir=desc"),
          url.contains("q=alice"),
          AppRouter.router.pageForRelativeUrl(url).contains(filtered),
          // The unfiltered listing still round-trips, even though `Route.withQuery` has no bare-path fallback to
          // prefer instead — see the route's doc comment for why the resulting `?`-suffixed URL is an accepted
          // tradeoff on this owner-only diagnostic page.
          AppRouter.router
            .pageForRelativeUrl(AppRouter.router.relativeUrlForPage(Page.GameResults("brave-otter")))
            .contains(Page.GameResults("brave-otter")),
        )
      },
    )
  }
}
