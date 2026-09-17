package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.api.WordApiClient
import gathedge.shared.domain.{Tag, WordLanguage}
import gathedge.shared.dto.TagPage
import org.scalajs.dom

/** A server-searched name dropdown over the wordlist catalog — "filter by wordlist", wherever a listing wants one.
  * First user: `AllGamesPage`'s wordlist filter; built as its own component because the ticket that asked for it
  * expects a second page to want the same control later.
  *
  * The dropdown is empty until the reader types something: an untouched or emptied box shows the placeholder, never
  * "every wordlist there is" — narrowing to a spoken need is the whole point of a search box, and the catalog is public
  * and can be large. Each keystroke is debounced [[debounceMs]] (the same delay `WordPicker`'s own dictionary search
  * uses) into `GET /api/tags/page?q=...`, which already answers a case-insensitive substring match across every
  * account's wordlists — the same call `TagsPage` itself reads. There is also no "create new" row: picking an existing
  * wordlist is the only thing this filter can mean.
  *
  * `languages`, when non-empty, narrows the server's page to tags whose `sourceLanguage`/`targetLanguage` pair contains
  * every language given — the server has no language filter of its own, so this happens client-side over the (small,
  * page-sized) response, in [[TagPicker.rank]]. Order does not matter for that narrowing, the same "contains" rule
  * `GameService.allGames` filters games by. The order the reader chose them in still matters for ranking.
  *
  * `selectedTag` is read only to *hydrate* the field (a bookmarked filter, or the page arriving with one already
  * chosen) — typing here never reads it back, so a reader retyping over an old choice is not fighting a value this
  * component keeps re-asserting. Clearing the filter is the caller's own control (a "×" button beside this one,
  * typically), not something typing an empty query does on its own — see [[clear]].
  */
final class TagPicker(
  languages: Signal[List[WordLanguage]],
  selectedTag: Signal[Option[Tag]],
  onSelect: Observer[Tag],
  placeholderText: String,
) {

  private val queryVar     = Var("")
  private val openVar      = Var(false)
  private val highlightVar = Var(-1)
  private val resultsVar   = Var(List.empty[Tag])
  private val typedBus     = new EventBus[String]()

  // `.now()` is only public on a `Var`, not on the `languages` signal the caller hands in — the same reason
  // `WordPicker` mirrors its own `language`/`partOfSpeech` — so a keyboard handler (not a subscription) can read the
  // current choice without it.
  private val languagesMirror = Var(List.empty[WordLanguage])

  private val debounceMs = 250

  /** How many rows the server is asked for — wider than [[maxRows]] shown, since the language filter below still has to
    * narrow the answer, and a page already limited to [[maxRows]] would leave too few once it does.
    */
  private val fetchSize = 20
  private val maxRows   = 8

  /** Empties the box — the caller's own "clear filter" control, since typing an empty query does not fire [[onSelect]]
    * on its own; see the class doc.
    */
  def clear(): Unit = { queryVar.set(""); openVar.set(false); highlightVar.set(-1); resultsVar.set(Nil) }

  private def currentOptions(): List[Tag] =
    TagPicker.rank(resultsVar.now(), queryVar.now().trim, languagesMirror.now()).take(maxRows)

  private val optionsSignal: Signal[List[Tag]] = {
    queryVar.signal.combineWith(resultsVar.signal, languages).map { case (raw, results, wanted) =>
      TagPicker.rank(results, raw.trim, wanted).take(maxRows)
    }
  }

  private def commit(tag: Tag): Unit = {
    queryVar.set(tag.name)
    openVar.set(false)
    onSelect.onNext(tag)
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
          typedBus.emit(text)
        },
      ),
      onFocus.mapToUnit --> Observer[Unit](_ => openVar.set(true)),
      // A click on a dropdown row blurs the input before its own `onClick` fires, so closing is deferred a beat — the
      // same reason `WordPicker` does this.
      onBlur.mapToUnit --> Observer[Unit](_ => dom.window.setTimeout(() => openVar.set(false), 120)),
      onKeyDown --> Observer[dom.KeyboardEvent](handleKey),
      // Hydrates the box from a filter the reader already had — a bookmarked `?tag=`, or the page arriving mid-session
      // with one chosen — without fighting whatever they are typing; see the class doc. A modifier of this element
      // itself (after `controlled`), on the same element as the `value <-- queryVar.signal` binding it writes into.
      selectedTag --> Observer[Option[Tag]](_.foreach(tag => queryVar.set(tag.name))),
    )

    div(
      cls := "relative",
      field,
      child.maybe <-- dropdown(),
      languages --> languagesMirror.writer,
      // Debounced server search — empty while the box is empty, so a focused-but-untyped field shows nothing, never
      // "every wordlist there is"; see the class doc.
      typedBus.events.debounce(debounceMs).flatMapSwitch { typed =>
        val trimmed = typed.trim
        if (trimmed.isEmpty) EventStream.fromValue(List.empty[Tag])
        else {
          WordApiClient
            .listTagsPage(search = Some(trimmed), pageSize = Some(fetchSize))
            .map(_.getOrElse(TagPage(Nil, 0L)).items)
        }
      } --> resultsVar.writer,
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

object TagPicker {

  /** Tags whose pair contains every chosen language — see the class doc for why order does not matter here. */
  private def languageFiltered(pool: List[Tag], wanted: List[WordLanguage]): List[Tag] = {
    pool.filter(tag => wanted.forall(lang => tag.sourceLanguage == lang || tag.targetLanguage == lang))
  }

  /** Whether `tag`'s own pair reads in the same order the reader chose — `0` (ranked first) when it does, `1` when
    * exactly two languages were chosen and it reads the other way round, and `0` (nothing to prefer) for zero or one
    * chosen language, where there is no "reversed" to distinguish it from.
    */
  private def orderRank(tag: Tag, wanted: List[WordLanguage]): Int = {
    wanted match {
      case a :: b :: Nil if tag.sourceLanguage == a && tag.targetLanguage == b => 0
      case _ :: _ :: Nil                                                       => 1
      case _                                                                   => 0
    }
  }

  /** `results` — a server search page, already narrowed by name — further narrowed to `wanted` and ranked: an exact
    * name match first, then a prefix match, then the rest, [[orderRank]] breaking ties within each, then
    * alphabetically. A `def` on the companion rather than a method of the class, so it is testable without a server:
    * the server owns the name search itself, but this decides what the dropdown shows and in what order.
    */
  def rank(results: List[Tag], search: String, wanted: List[WordLanguage]): List[Tag] = {
    val low = search.toLowerCase
    languageFiltered(results, wanted).sortBy { tag =>
      val name     = tag.name.toLowerCase
      val nameRank = if (name == low) 0 else if (name.startsWith(low)) 1 else 2
      (nameRank, orderRank(tag, wanted), name)
    }
  }
}
