package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, WordCollect}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.Tag
import gathedge.shared.dto.TagResponse
import gathedge.shared.i18n.UiKeys

/** `/tags/new` mints an empty tag straight away and hands off to [[TagEditorPage]] — creating and editing a tag are one
  * screen, so there is nothing for this route to render of its own. The name is a free "Untitled N" so the per-owner
  * name uniqueness never trips; the reader renames it inline on the editor.
  */
object TagCreatePage {

  def render(): HtmlElement = AppShell.render(Page.TagCreate, new TagCreatePage().render())

  private val base = () => I18n.t(UiKeys.tagsEditorDefaultName)

  /** The first name of the form `Untitled`, `Untitled 2`, `Untitled 3`, … the reader does not already own. */
  private def freeName(existing: List[Tag]): String = {
    val taken = existing.filter(_.ownedByMe).map(_.name.trim.toLowerCase).toSet
    val root  = base()
    LazyList
      .from(1)
      .map(n => if (n == 1) root else s"$root $n")
      .find(name => !taken.contains(name.toLowerCase))
      .getOrElse(s"$root ${System.currentTimeMillis()}")
  }
}

private final class TagCreatePage {

  private val errorVar = Var(Option.empty[String])
  private val startBus = new EventBus[Unit]()

  def render(): HtmlElement = {
    div(
      cls := "max-w-3xl mx-auto p-4",
      Alert.maybeError(errorVar.signal),
      p(cls := "opacity-60", I18n.t(UiKeys.commonCreate), "…"),
      startBus.events
        .flatMapSwitch(_ => WordApiClient.listTags)
        .flatMapSwitch {
          case Right(tags) => WordApiClient.createTag(TagCreatePage.freeName(tags))
          case Left(err)   => EventStream.fromValue(Left(err))
        } --> Observer[Either[ApiError, TagResponse]] {
        case Right(response) =>
          WordCollect.storeCollectTag(Some(response.tag.id))
          AppRouter.router.replaceState(Page.TagDetail(response.tag.id))
        case Left(err)       =>
          errorVar.set(Some(err.message))
      },
      onMountCallback(_ => startBus.emit(())),
    )
  }
}
