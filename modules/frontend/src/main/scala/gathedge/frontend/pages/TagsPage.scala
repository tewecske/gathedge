package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, TagImportDialog}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.util.Download
import gathedge.shared.domain.{GroupRef, Tag}
import gathedge.shared.dto.TagExportFile
import gathedge.shared.i18n.UiKeys
import zio.json._

/** Every tag the caller may edit — their own, plus any tag a group they belong to has opened to them — the same set
  * `WordCollect.mineOptions` offers a tick or a chip, laid out here as a table instead of a dropdown. Reached from the
  * navigation bar and from the collection bar's "All tags" button. The "New tag" button is here rather than on the bar:
  * a tag is minted from the list of the tags there already, which is what shows the reader whether one fits.
  */
object TagsPage {

  def render(): HtmlElement = {
    AppShell.render(Page.Tags, new TagsPage().render())
  }
}

private class TagsPage {

  private val tagsVar = Var(List.empty[Tag])

  private val errorVar: Var[Option[String]] = Var(None)

  private val reloadBus    = new EventBus[Unit]()
  private val exportAllBus = new EventBus[Unit]()

  private val importDialog = new TagImportDialog(onImported = Observer[Unit](_ => reloadBus.emit(())))

  /** One section of the table: a heading (`None` for the reader's own, un-grouped tags) and the tags under it, already
    * sorted the way [[WordCollect.mineOptions]] sorts its `<optgroup>`s.
    */
  private def sections(tags: List[Tag]): List[(Option[GroupRef], List[Tag])] = {
    val editable         = tags.filter(_.editableByMe)
    val (mine, byOthers) = editable.partition(_.ownedByMe)
    val mineSection      = Option.when(mine.nonEmpty)(None -> mine.sortBy(_.name.toLowerCase))
    val groupSections    = byOthers
      .groupBy(_.group)
      .toList
      .sortBy { case (group, _) => group.map(_.name.toLowerCase).getOrElse("") }
      .map { case (group, groupTags) => group -> groupTags.sortBy(_.name.toLowerCase) }
    mineSection.toList ++ groupSections
  }

  def render(): HtmlElement = {
    div(
      cls := "max-w-2xl mx-auto",
      Alert.maybeError(errorVar.signal),
      div(
        cls := "card bg-base-100 shadow mt-4",
        div(
          cls := "card-body",
          div(
            cls := "flex flex-wrap items-center justify-between gap-2",
            h1(cls := "card-title text-2xl", I18n.t(UiKeys.tagsListTitle)),
            div(
              cls  := "flex gap-2",
              a(
                cls := "btn btn-sm btn-primary",
                AppRouter.router.navigateTo(Page.TagCreate),
                I18n.t(UiKeys.tagsCreate),
              ),
              button(
                cls := "btn btn-sm",
                typ := "button",
                I18n.t(UiKeys.tagsExportAllButton),
                onClick.mapToUnit --> exportAllBus.writer,
              ),
              importDialog.renderButton(),
            ),
          ),
          renderList(),
        ),
      ),
      importDialog.renderModal(),
      reloadBus.events.flatMapSwitch(_ => WordApiClient.listTags) -->
        Observer[Either[ApiError, List[Tag]]] {
          case Right(tags) =>
            tagsVar.set(tags)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      exportAllBus.events.flatMapSwitch(_ => WordApiClient.exportOwnedTags) -->
        Observer[Either[ApiError, TagExportFile]] {
          case Right(file) =>
            Download.text("my-tags.json", file.toJson)
          case Left(err)   =>
            errorVar.set(Some(err.message))
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def renderList(): HtmlElement = {
    div(
      child.maybe <--
        tagsVar.signal
          .map(sections)
          .map(list => Option.when(list.isEmpty)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.tagsListEmpty)))),
      div(
        cls := "overflow-x-auto",
        table(
          cls := "table",
          thead(
            tr(
              th(I18n.t(UiKeys.tagsListColName)),
              th(I18n.t(UiKeys.tagsListColWords)),
            )
          ),
          tbody(children <-- tagsVar.signal.map(sections).map(_.flatMap(renderSection))),
        ),
      ),
    )
  }

  private def renderSection(section: (Option[GroupRef], List[Tag])): List[HtmlElement] = {
    val (group, tags) = section
    renderSeparator(group) :: tags.map(renderRow)
  }

  private def renderSeparator(group: Option[GroupRef]): HtmlElement = {
    tr(
      cls := "bg-base-200",
      th(
        colSpan := 2,
        group match {
          case Some(g) =>
            a(cls := "link link-hover", AppRouter.router.navigateTo(Page.GroupDetail(g.id)), g.name)
          case None    =>
            span(I18n.t(UiKeys.tagsListYours))
        },
      ),
    )
  }

  private def renderRow(tag: Tag): HtmlElement = {
    tr(
      td(
        a(
          cls := "link link-hover",
          AppRouter.router.navigateTo(Page.TagDetail(tag.id)),
          tag.name,
        )
      ),
      td(tag.wordCount.toString),
    )
  }
}
