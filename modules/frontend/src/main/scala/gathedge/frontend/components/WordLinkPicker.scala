package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{PartOfSpeech, WordLanguage, WordLinkKind}
import gathedge.shared.dto.{TagPairWord, WordLinkInput}
import gathedge.shared.i18n.UiKeys

/** The control that adds a word link: what the linked word is, and the word itself. The word page and the wordlist
  * editor both use it.
  *
  * The select offers the kinds [[WordLinkKind.offered]] gives for a word of `language` and `partOfSpeech`, the rule the
  * server applies again. A counterpart of another gender is offered only for a noun of a language with genders. When
  * the word changes and the kind picked is no longer offered, the first one offered takes its place.
  *
  * The word box is a [[WordPicker]] in the word's own language, since a link never crosses languages. It offers the
  * typed text as a new word, as the other boxes do. [[current]] is the link once both halves are picked.
  */
final class WordLinkPicker(
  language: Signal[WordLanguage],
  partOfSpeech: Signal[Option[PartOfSpeech]],
  // A word was committed in the box. The word page adds the link at once.
  onCommit: Observer[WordLinkInput] = Observer.empty[WordLinkInput],
) {

  private val kindVar = Var(Option.empty[WordLinkKind])
  private val wordVar = Var(Option.empty[TagPairWord])

  private val offeredSignal: Signal[List[WordLinkKind]] = {
    language.combineWith(partOfSpeech).map { case (lang, pos) =>
      WordLinkKind.offered(lang, pos.getOrElse(PartOfSpeech.Other))
    }
  }

  private val picker = new WordPicker(
    language = language,
    partOfSpeech = partOfSpeech,
    onCommit = Observer[TagPairWord] { ref =>
      wordVar.set(Some(ref))
      current.foreach(onCommit.onNext)
    },
    placeholderSignal = Val(I18n.t(UiKeys.wordLinkSearch)),
    inlineLabel = Some(I18n.t(UiKeys.tagsEditorLinkLabel)),
  )

  /** The link to add, once a kind and a word are picked. */
  def current: Option[WordLinkInput] = {
    for {
      kind <- kindVar.now()
      word <- wordVar.now()
    } yield WordLinkInput(word, kind)
  }

  /** Empties the word box and keeps the kind: a second link is often of the same kind. */
  def clear(): Unit = {
    wordVar.set(None)
    picker.clear()
  }

  def focus(): Unit = picker.focus()

  def render(): HtmlElement = {
    div(
      cls := "flex flex-col gap-2",
      label(
        cls := "select select-sm w-full",
        span(cls := "label", I18n.t(UiKeys.wordLinkKindLabel)),
        select(
          children <-- offeredSignal.map(
            _.map(kind => option(value := WordLinkKind.code(kind), Labels.wordLink(kind)))
          ),
          controlled(
            value <-- kindVar.signal.map(_.map(WordLinkKind.code).getOrElse("")),
            onChange.mapToValue --> Observer[String](code => kindVar.set(WordLinkKind.fromCode(code))),
          ),
        ),
      ),
      picker.render(),
      offeredSignal --> Observer[List[WordLinkKind]] { offered =>
        if (!kindVar.now().exists(offered.contains)) kindVar.set(offered.headOption)
      },
    )
  }
}
