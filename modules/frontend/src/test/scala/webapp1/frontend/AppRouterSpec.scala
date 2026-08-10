package webapp1.frontend

import webapp1.frontend.i18n.CurrentLocale
import webapp1.shared.domain.Locale.urlPrefix
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
          AppRouter.router.relativeUrlForPage(Page.Groups) == s"$prefix/groups",
          AppRouter.router.relativeUrlForPage(Page.SignIn) == s"$prefix/sign-in",
          AppRouter.router.relativeUrlForPage(Page.Settings) == s"$prefix/settings",
          AppRouter.router.relativeUrlForPage(Page.AdminSystem) == s"$prefix/admin/system",
        )
      },
      test("so does a route with path parameters") {
        assertTrue(
          AppRouter.router.relativeUrlForPage(Page.GroupDetail(7)) == s"$prefix/groups/7",
          AppRouter.router.relativeUrlForPage(Page.GroupMembers(7)) == s"$prefix/groups/7/members",
          AppRouter.router.relativeUrlForPage(Page.AdminUserDetail(3)) == s"$prefix/admin/users/3",
          AppRouter.router.relativeUrlForPage(Page.VerifyEmail("tok")) == s"$prefix/verify-email/tok",
        )
      },
      // The home route is the one whose un-prefixed form is bare `/`, so it is the one where a
      // missing prefix would be least obvious.
      test("the home route is the prefix itself") {
        assertTrue(AppRouter.router.relativeUrlForPage(Page.Home) == s"$prefix/")
      },
      // The invitation and verification links in transactional email are built server-side, by
      // string concatenation, against these same patterns. If the two ever disagree, a link that
      // arrives in someone's inbox lands on NotFoundPage — and no test on either side alone sees it.
      test("the email links the server builds match the routes that receive them") {
        assertTrue(
          AppRouter.router.relativeUrlForPage(Page.VerifyEmail("abc")) == s"$prefix/verify-email/abc",
          AppRouter.router.relativeUrlForPage(Page.AcceptInvite("abc")) == s"$prefix/invitations/abc",
        )
      },
    )
  }
}
