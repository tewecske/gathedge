package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, HelpIcon, Labels, TagImportDialog}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.frontend.util.Download
import gathedge.shared.domain.{GroupRef, Tag}
import gathedge.shared.dto.TagExportFile
import gathedge.shared.i18n.UiKeys
import zio.json._

/** The whole wordlist catalog — every wordlist there is — laid out as a table instead of the dropdown `WordCollect`
  * offers. Reached from the navigation bar and from the collection bar's "All tags" button.
  *
  * Everyone sees every wordlist. A signed-in reader's own wordlists come first, then any a group they belong to has
  * opened to them, then everyone else's under "Other wordlists"; the last group is read-only, the same as what the
  * editor shows a non-owner. A signed-out visitor sees one flat list, since none of it is theirs. The "New wordlist",
  * "Export all" and "Import" controls are shown only when signed in ("New wordlist" always, since it mints a guest).
  * "New wordlist" is here rather than on the bar: a wordlist is minted from the list of the ones there already, which
  * is what shows the reader whether one fits.
  */
object TagsPage {

  def render(): HtmlElement = {
    AppShell.render(Page.Tags, new TagsPage().render())
  }

  /** A table section's heading. `Yours` and `Others` are fixed labels; `Group` links to the study group that opened its
    * wordlists to the reader.
    */
  private enum Heading {
    case Yours
    case Others
    case Group(ref: GroupRef)
  }
}

private class TagsPage {

  private val tagsVar = Var(List.empty[Tag])

  private val errorVar: Var[Option[String]] = Var(None)

  private val reloadBus    = new EventBus[Unit]()
  private val exportAllBus = new EventBus[Unit]()

  private val signedInSignal = AppState.isSignedInSignal

  private val importDialog = new TagImportDialog(onImported = Observer[Unit](_ => reloadBus.emit(())))

  import TagsPage.Heading

  /** The table's sections, always over the whole catalog. Signed out: one headingless section, sorted by name, since
    * none of it is the reader's. Signed in: the reader's own wordlists under "Your wordlists", then one section per
    * study group that shared its wordlists, then everyone else's under "Other wordlists" — each block sorted by name,
    * the way [[WordCollect.mineOptions]] sorts its `<optgroup>`s.
    */
  private def sections(tags: List[Tag], signedIn: Boolean): List[(Option[Heading], List[Tag])] = {
    def byName(list: List[Tag]): List[Tag] = list.sortBy(_.name.toLowerCase)

    if (!signedIn) {
      List(None -> byName(tags))
    } else {
      val (editable, others) = tags.partition(_.editableByMe)
      val (mine, byGroup)    = editable.partition(_.ownedByMe)
      val mineSection        = Option.when(mine.nonEmpty)(Option(Heading.Yours) -> byName(mine))
      val groupSections      = byGroup
        .groupBy(_.group)
        .toList
        .sortBy { case (group, _) => group.map(_.name.toLowerCase).getOrElse("") }
        .map { case (group, groupTags) =>
          group.map(Heading.Group.apply) -> byName(groupTags)
        }
      val othersSection      = Option.when(others.nonEmpty)(Option(Heading.Others) -> byName(others))
      mineSection.toList ++ groupSections ++ othersSection.toList
    }
  }

  private val sectionsSignal: Signal[List[(Option[Heading], List[Tag])]] =
    Signal.combine(tagsVar.signal, signedInSignal).map { case (tags, signedIn) => sections(tags, signedIn) }

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
            h1(
              cls := "card-title text-2xl flex items-center gap-1",
              span(I18n.t(UiKeys.tagsListTitle)),
              HelpIcon.render(I18n.t(UiKeys.helpTags)),
            ),
            div(
              cls := "flex gap-2",
              a(
                cls := "btn btn-sm btn-primary",
                AppRouter.router.navigateTo(Page.TagCreate),
                I18n.t(UiKeys.tagsCreate),
              ),
              // Both act on the caller's own wordlists, so a signed-out visitor — who owns none — is not shown them.
              child.maybe <-- signedInSignal.map(
                Option.when(_)(
                  button(
                    cls := "btn btn-sm",
                    typ := "button",
                    I18n.t(UiKeys.tagsExportAllButton),
                    onClick.mapToUnit --> exportAllBus.writer,
                  )
                )
              ),
              child.maybe <-- signedInSignal.map(Option.when(_)(importDialog.renderButton())),
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
        sectionsSignal
          .map(list => list.forall { case (_, tags) => tags.isEmpty })
          .map(empty => Option.when(empty)(p(cls := "text-sm opacity-70", I18n.t(UiKeys.tagsListEmpty)))),
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
          tbody(children <-- sectionsSignal.map(_.flatMap(renderSection))),
        ),
      ),
    )
  }

  /** The signed-out catalog is one headingless section, so its separator row is dropped; every signed-in section
    * carries a heading ("Your wordlists", a group name, or "Other wordlists").
    */
  private def renderSection(section: (Option[Heading], List[Tag])): List[HtmlElement] = {
    val (heading, tags) = section
    heading.map(renderSeparator).toList ++ tags.map(renderRow)
  }

  private def renderSeparator(heading: Heading): HtmlElement = {
    tr(
      cls := "bg-base-200",
      th(
        colSpan := 2,
        heading match {
          case Heading.Group(g) =>
            a(cls := "link link-hover", AppRouter.router.navigateTo(Page.GroupDetail(g.id)), g.name)
          case Heading.Yours    =>
            span(I18n.t(UiKeys.tagsListYours))
          case Heading.Others   =>
            span(I18n.t(UiKeys.tagsListOthers))
        },
      ),
    )
  }

  private def renderRow(tag: Tag): HtmlElement = {
    tr(
      td(
        span(cls := "font-mono text-xs opacity-70 mr-2", Labels.tagCodes(tag)),
        a(
          cls    := "link link-hover",
          AppRouter.router.navigateTo(Page.TagDetail(tag.id)),
          tag.name,
        ),
      ),
      td(tag.wordCount.toString),
    )
  }
}
