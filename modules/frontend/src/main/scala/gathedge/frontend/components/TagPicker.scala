package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.shared.domain.{Tag, WordLanguage}
import org.scalajs.dom

/** A name-search dropdown over an already-fetched pool of tags — "filter by wordlist", wherever a listing wants one.
  * First user: `AllGamesPage`'s wordlist filter; built as its own component because the ticket that asked for it
  * expects a second page to want the same control later.
  *
  * Unlike [[WordPicker]] this never calls the server itself: `tags` is the whole pool the caller already holds (the
  * catalog is small — the same `GET /api/tags` every tag `<select>` on the site reads unpaged), and this component only
  * narrows and ranks it client-side as the reader types. There is also no "create new" row — picking an existing tag is
  * the only thing a filter can mean.
  *
  * `languages`, when non-empty, narrows the pool to tags whose `sourceLanguage`/`targetLanguage` pair contains every
  * language given — order does not matter for that narrowing, the same "contains" rule `GameService.allGames` filters
  * games by. The order the reader chose them in still matters for ranking: with the pool already narrowed to pairs that
  * contain both, a tag whose own pair reads in that same order is offered above one that reads the other way round —
  * ahead of plain alphabetical order, but behind how well the typed text matches the name.
  *
  * `selected` is read only to *hydrate* the field (a bookmarked filter, or a page arriving with one already chosen) —
  * typing here never reads it back, so a reader retyping over an old choice is not fighting a value this component
  * keeps re-asserting. Clearing the filter is the caller's own control (a "×" button beside this one, typically), not
  * something typing an empty query does on its own: an empty box while closed still shows the placeholder, not "every
  * tag ever", so [[onSelect]] fires only from [[commit]], on a dropdown pick.
  */
final class TagPicker(
  tags: Signal[List[Tag]],
  languages: Signal[List[WordLanguage]],
  selected: Signal[Option[Long]],
  onSelect: Observer[Option[Long]],
  placeholderText: String,
) {

  private val queryVar     = Var("")
  private val openVar      = Var(false)
  private val highlightVar = Var(-1)

  // `.now()` is only public on a `Var`, not on the `Signal`s the caller hands in — the same reason `WordPicker` mirrors
  // its own `language`/`partOfSpeech` — so a keyboard handler (not a subscription) can read the current pool and
  // languages without them.
  private val tagsMirror      = Var(List.empty[Tag])
  private val languagesMirror = Var(List.empty[WordLanguage])

  private val maxRows = 8

  /** Empties the box — the caller's own "clear filter" control, since typing an empty query does not fire [[onSelect]]
    * on its own; see the class doc.
    */
  def clear(): Unit = { queryVar.set(""); openVar.set(false); highlightVar.set(-1) }

  /** Tags whose pair contains every chosen language — see the class doc for why order does not matter here. */
  private def languageFiltered(pool: List[Tag], wanted: List[WordLanguage]): List[Tag] = {
    pool.filter(tag => wanted.forall(lang => tag.sourceLanguage == lang || tag.targetLanguage == lang))
  }

  /** Whether `tag`'s own pair reads in the same order the reader chose — `0` (ranked first) when it does, `1` when the
    * pool was narrowed to exactly two languages read the other way round, and `0` (nothing to prefer) for zero or one
    * chosen language, where there is no "reversed" to distinguish it from.
    */
  private def orderRank(tag: Tag, wanted: List[WordLanguage]): Int = {
    wanted match {
      case a :: b :: Nil if tag.sourceLanguage == a && tag.targetLanguage == b => 0
      case _ :: _ :: Nil                                                       => 1
      case _                                                                   => 0
    }
  }

  /** `pool`, already narrowed to `wanted`, matching `search` by name — ranked exact/prefix/contains first, then by
    * [[orderRank]], then alphabetically — and cut to [[maxRows]].
    */
  private def matches(pool: List[Tag], search: String, wanted: List[WordLanguage]): List[Tag] = {
    val low      = search.toLowerCase
    val narrowed = languageFiltered(pool, wanted)
    val bySearch = if (low.isEmpty) narrowed else narrowed.filter(_.name.toLowerCase.contains(low))
    bySearch
      .sortBy { tag =>
        val name     = tag.name.toLowerCase
        val nameRank = if (name == low) 0 else if (name.startsWith(low)) 1 else 2
        (nameRank, orderRank(tag, wanted), name)
      }
      .take(maxRows)
  }

  private val optionsSignal: Signal[List[Tag]] = {
    queryVar.signal.combineWith(tags, languages).map { case (raw, pool, wanted) => matches(pool, raw.trim, wanted) }
  }

  private def currentOptions(): List[Tag] = matches(tagsMirror.now(), queryVar.now().trim, languagesMirror.now())

  private def commit(tag: Tag): Unit = {
    queryVar.set(tag.name)
    openVar.set(false)
    onSelect.onNext(Some(tag.id))
  }

  private def handleKey(ev: dom.KeyboardEvent): Unit = {
    ev.key match {
      case "Enter"     =>
        ev.preventDefault()
        val list = currentOptions()
        val h    = highlightVar.now()
        if (list.nonEmpty) commit(list(if (h >= 0 && h < list.size) h else 0))
      case "Tab"       =>
        openVar.set(false)
      case "ArrowDown" =>
        ev.preventDefault()
        openVar.set(true)
        highlightVar.update(h => Math.min(h + 1, currentOptions().size - 1))
      case "ArrowUp"   =>
        ev.preventDefault()
        highlightVar.update(h => if (h <= 0) 0 else h - 1)
      case "Escape"    =>
        openVar.set(false)
      case _           =>
        ()
    }
  }

  def render(): HtmlElement = {
    val field = input(
      cls         := "input input-sm w-full",
      typ         := "text",
      placeholder := placeholderText,
      controlled(
        value <-- queryVar.signal.distinct,
        onInput.mapToValue --> Observer[String] { text =>
          Var.set(queryVar -> text, highlightVar -> -1)
          openVar.set(true)
        },
      ),
      onFocus.mapToUnit --> Observer[Unit](_ => openVar.set(true)),
      // A click on a dropdown row blurs the input before its own `onClick` fires, so closing is deferred a beat — the
      // same reason `WordPicker` does this.
      onBlur.mapToUnit --> Observer[Unit](_ => dom.window.setTimeout(() => openVar.set(false), 120)),
      onKeyDown --> Observer[dom.KeyboardEvent](handleKey),
      // Hydrates the box from a filter the reader already had — a bookmarked `?tag=`, or the page arriving mid-session
      // with one chosen — without fighting whatever they are typing; see the class doc. A modifier of this element
      // itself (after `controlled`), not of the wrapping `div`: on the same element as the `value <-- queryVar.signal`
      // binding it writes into, it is guaranteed to activate no earlier than that binding is ready for it.
      selected.combineWith(tags) --> Observer[(Option[Long], List[Tag])] { case (id, pool) =>
        id.flatMap(tid => pool.find(_.id == tid)).foreach(tag => queryVar.set(tag.name))
      },
    )

    div(
      cls := "relative",
      field,
      child.maybe <-- dropdown(),
      tags --> tagsMirror.writer,
      languages --> languagesMirror.writer,
    )
  }

  private def dropdown(): Signal[Option[HtmlElement]] = {
    optionsSignal.combineWith(openVar.signal.distinct).map { case (list, open) =>
      Option.when(open && list.nonEmpty)(
        ul(
          cls := "menu menu-sm bg-base-100 rounded-box shadow absolute z-10 w-full mt-1 max-h-60 overflow-y-auto",
          list.zipWithIndex.map { case (tag, i) =>
            li(
              a(
                cls("menu-active") <-- highlightVar.signal.map(_ == i),
                div(
                  cls := "flex w-full items-center justify-between gap-2",
                  span(s"${Labels.tagCodes(tag)} ${tag.name}"),
                  span(cls := "badge badge-ghost badge-xs", tag.wordCount.toString),
                ),
                onMouseEnter.mapToUnit --> Observer[Unit](_ => highlightVar.set(i)),
                onClick.mapToUnit --> Observer[Unit](_ => commit(tag)),
              )
            )
          },
        )
      )
    }
  }
}
