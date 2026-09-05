package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, WordCollect}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.listing.WordQuery
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.dto.TagResponse
import gathedge.shared.i18n.UiKeys

/** `/tags/new` mints a tag straight away and hands off to [[TagEditorPage]] — creating and editing a tag are one
  * screen, so there is nothing for this route to render of its own. The name is a free "Untitled N" so the per-owner
  * name uniqueness never trips; the reader renames it inline on the editor. The mandatory language pair is guessed from
  * the reader's current collect tag, then the words page's own direction, then `de → hu`; the editor's language selects
  * let them change it until the tag has a pair.
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

  /** The pair a fresh tag is created with: the reader's current collect tag's, else the words page's stored direction,
    * else the deployment default.
    */
  private def defaultLanguages(existing: List[Tag]): (WordLanguage, WordLanguage) = {
    existing
      .find(tag => WordCollect.storedCollectTag.contains(tag.id))
      .map(tag => (tag.sourceLanguage, tag.targetLanguage))
      .orElse(WordQuery.storedFilter.map(query => (query.language, query.target)))
      .getOrElse((WordLanguage.De, WordLanguage.Hu))
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
          case Right(tags) =>
            val (source, target) = TagCreatePage.defaultLanguages(tags)
            WordApiClient.createTag(TagCreatePage.freeName(tags), source, target)
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
