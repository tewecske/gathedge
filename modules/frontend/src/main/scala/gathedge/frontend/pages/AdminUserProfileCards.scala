package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{Alert, Formats}
import gathedge.frontend.i18n.I18n
import gathedge.shared.dto.AdminUserProfile
import gathedge.shared.i18n.UiKeys

/** The account's own profile, as the administrator's account screen shows it: the daily streak, the level and the
  * achievements, a link to the play history, and the progress shares in both directions.
  *
  * The account sees each of these on a tab of its profile. The cards here draw them with the same code those tabs use
  * (`ProfilePage.renderStats`, `AchievementsPage.renderLevel`, `AchievementsPage.renderList`), so the two views show
  * the same thing. Read-only: nothing here changes the account.
  */
private class AdminUserProfileCards(userId: Long) {

  private val profileVar: Var[Option[AdminUserProfile]] = Var(None)
  private val errorVar: Var[Option[String]]             = Var(None)

  def render(): HtmlElement = {
    div(
      cls := "flex flex-col gap-4 mt-6",
      h2(cls := "text-xl font-bold", I18n.t(UiKeys.profileTitle)),
      Alert.maybeError(errorVar.signal),
      child <-- profileVar.signal.combineWith(errorVar.signal).map {
        case (Some(profile), _) =>
          renderProfile(profile)
        // The alert above says what went wrong; empty cards would say the account has nothing.
        case (None, Some(_))    =>
          div()
        case (None, None)       =>
          span(cls := "loading loading-spinner")
      },
      AdminApiClient.userProfile(userId) --> Observer[Either[ApiError, AdminUserProfile]] {
        case Right(profile) =>
          profileVar.set(Some(profile))
        case Left(err)      =>
          errorVar.set(Some(err.message))
      },
    )
  }

  private def renderProfile(profile: AdminUserProfile): HtmlElement = {
    div(
      cls := "flex flex-col gap-4",
      card(I18n.t(UiKeys.profileStreakCard), ProfilePage.renderStats(profile.streak)),
      AchievementsPage.renderLevel(profile.achievements.level),
      card(I18n.t(UiKeys.profileTabAchievements), AchievementsPage.renderList(profile.achievements.achievements)),
      // The profile's fourth tab is paged, so it keeps its own screen.
      a(
        cls := "btn btn-sm w-fit",
        AppRouter.router.navigateTo(Page.AdminUserPlays(userId)),
        I18n.t(UiKeys.adminUserPlaysColHeader),
      ),
      card(
        I18n.t(UiKeys.profileTabShared),
        shareList(
          I18n.t(UiKeys.adminUserProfileSharedWith),
          profile.sharedWithMe.map(row => (row.sharerUserId, row.email, row.sharedAt)),
        ),
        shareList(
          I18n.t(UiKeys.adminUserProfileSharingWith),
          profile.sharingWith.map(row => (row.userId, row.email, row.sharedAt)),
        ),
      ),
    )
  }

  private def card(title: String, content: Modifier[HtmlElement]*): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(cls := "card-body", h3(cls := "card-title text-lg", title), content),
    )
  }

  /** One direction of the progress shares. Each row links to the other account's own screen. A guest has no address, so
    * it shows the guest badge in its place.
    */
  private def shareList(title: String, rows: List[(Long, Option[String], Long)]): HtmlElement = {
    div(
      h4(cls := "font-semibold", title),
      if (rows.isEmpty) {
        p(cls := "text-sm opacity-70", I18n.t(UiKeys.adminUserProfileNoShares))
      } else {
        ul(
          cls := "flex flex-col divide-y divide-base-300",
          rows.map { case (id, email, sharedAt) =>
            li(
              cls := "flex flex-wrap items-center justify-between gap-2 py-2",
              a(
                cls    := "link",
                AppRouter.router.navigateTo(Page.AdminUserDetail(id)),
                email.getOrElse(I18n.t(UiKeys.sharedProgressGuestBadge)),
              ),
              span(cls := "text-sm opacity-70", I18n.t(UiKeys.adminUserProfileSince, Formats.date(sharedAt))),
            )
          },
        )
      },
    )
  }
}
