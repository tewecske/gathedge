package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, Alert, AppShell, Formats}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.dto.{DuplicateGame, DuplicateGameGroup}
import gathedge.shared.i18n.UiKeys

/** The duplicate-game report: every set of wordlists more than one game was built from, one block per set.
  *
  * A report, not an enforcement screen. One game per wordlist set is a recommendation — a game may span several
  * wordlists, and nothing refuses a second game over the same set — so this offers no delete: an administrator reads
  * it, opens the games it names, and decides. It is the counterpart of the "Play game" button the wordlist catalog and
  * one wordlist's own page draw in place of "Create game", which is what keeps most sets down to one game.
  *
  * Built like `AdminWordFormsPage`: one read on mount, no listing state of its own.
  */
object AdminDuplicateGamesPage {
  def render(): HtmlElement = AppShell.render(Page.AdminDuplicateGames, new AdminDuplicateGamesPage().render())
}

private class AdminDuplicateGamesPage {

  private val groupsVar: Var[Option[List[DuplicateGameGroup]]] = Var(None)

  private val errorVar: Var[Option[String]] = Var(None)

  private val loadBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminDuplicateGamesTitle)),
      AdminSubmenu.render(Page.AdminDuplicateGames),
      Alert.maybeError(errorVar.signal),
      p(cls  := "text-sm opacity-60 mb-4", I18n.t(UiKeys.adminDuplicateGamesHint)),
      child <-- groupsVar.signal.map(renderGroups),
      loadBus.events.flatMapSwitch(_ => AdminApiClient.duplicateGames) -->
        Observer[Either[ApiError, List[DuplicateGameGroup]]] {
          case Right(groups) =>
            Var.set(groupsVar -> Some(groups), errorVar -> None)
          case Left(err)     =>
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => loadBus.emit(())),
    )
  }

  private def renderGroups(groups: Option[List[DuplicateGameGroup]]): HtmlElement = {
    groups match {
      case None                       =>
        span(cls := "loading loading-spinner loading-sm")
      case Some(rows) if rows.isEmpty =>
        p(cls := "text-sm opacity-60", I18n.t(UiKeys.adminDuplicateGamesEmpty))
      case Some(rows)                 =>
        div(
          p(cls   := "text-sm opacity-60 mb-2", I18n.plural(UiKeys.adminDuplicateGamesCount, rows.size.toLong)),
          div(cls := "flex flex-col gap-4", rows.map(renderGroup)),
        )
    }
  }

  private def renderGroup(group: DuplicateGameGroup): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow",
      div(
        cls := "card-body p-4",
        div(
          cls := "flex flex-wrap items-center gap-2",
          span(cls := "label-text text-xs", I18n.t(UiKeys.adminDuplicateGamesWordlists)),
          group.tags.map(tag => {
            a(
              cls := "badge badge-sm badge-soft",
              AppRouter.router.navigateTo(Page.TagDetail(tag.id)),
              tag.name,
            )
          }),
        ),
        div(
          cls := "overflow-x-auto mt-2",
          table(
            cls := "table table-sm",
            thead(
              tr(
                th(I18n.t(UiKeys.adminDuplicateGamesColGame)),
                th(I18n.t(UiKeys.adminDuplicateGamesColOwner)),
                th(cls := "text-right", I18n.t(UiKeys.adminDuplicateGamesColPlays)),
                th(I18n.t(UiKeys.adminDuplicateGamesColCreated)),
              )
            ),
            tbody(group.games.map(renderGame)),
          ),
        ),
      ),
    )
  }

  private def renderGame(game: DuplicateGame): HtmlElement = {
    tr(
      cls := "hover",
      td(
        a(
          cls := "link link-hover",
          AppRouter.router.navigateTo(Page.GameInstance(game.slug)),
          game.name,
        )
      ),
      // A guest owns no address, which the badge says rather than leaving the cell blank — the same choice
      // `GamePlaySummary.playerIsGuest` makes in the owner-facing plays table.
      td(
        game.ownerEmail match {
          case Some(email) =>
            span(email)
          case None        =>
            span(cls := "badge badge-sm", I18n.t(UiKeys.adminDuplicateGamesOwnerGuest))
        }
      ),
      td(cls := "text-right", game.playCount.toString),
      td(Formats.dateTime(game.createdAt)),
    )
  }
}
