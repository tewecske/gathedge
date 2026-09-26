package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.WordApiClient
import gathedge.frontend.components.{Labels, WordPicker}
import gathedge.frontend.i18n.I18n
import gathedge.frontend.state.AppState
import gathedge.shared.domain.{PartOfSpeech, Word, WordLanguage}
import gathedge.shared.dto.{
  MainWordLink,
  MainWordUnlink,
  TagEntryInput,
  TagEntryWord,
  TagPairWord,
  WordDetail,
  WordFormRef,
}
import gathedge.shared.i18n.{MessageKeys, UiKeys}
import org.scalajs.dom

/** The one editor for a wordlist row. The add row and a row's edit mode are both this editor.
  *
  * It holds the two words of the row, the row's part of speech, the reader's note beside each word, and the main words
  * each word is a form of. An import writes the notes and the forms from the file. Here the reader writes them by hand,
  * so a word typed in can carry them too. Save sends all of it in one request: `onSubmit` gets the body, and the page
  * sends it to the add or the edit endpoint.
  *
  * '''The part of speech is the row's.''' Both word boxes and both main-word boxes search it, a new word is created
  * with it, and the form types on offer are the ones the dictionary's own forms carry for it, so a noun is never
  * offered `past`. A word that has another part of speech is given it on save. On the add row it starts empty and the
  * first dictionary word picked sets it.
  *
  * '''Shared data is guarded.''' A word the reader did not make is an administrator's to change, and so is a link the
  * reader did not make. An administrator confirms a warning before changing dictionary data. The server applies the
  * same rule, so the page is not what enforces it; the page only asks first.
  *
  * The grid keeps the two word boxes on one line on a wide screen: a German box has its article buttons above it, so
  * the boxes sit at the bottom of their shared row, and the notes and the forms follow in rows of their own.
  */
private[pages] final class TagEntryEditor(
  leftLanguage: Signal[WordLanguage],
  rightLanguage: Signal[WordLanguage],
  seed: Option[TagEntryEditor.Seed],
  onSubmit: Observer[TagEntryInput],
  onCancel: Observer[Unit] = Observer.empty,
) {

  import TagEntryEditor.{Gate, PendingLink, SeedSide, WordGate}

  private val editing = seed.isDefined

  /** The row's part of speech. `None` only on the add row before a word settles it: the word boxes then search every
    * kind, and no form can be added yet.
    */
  private val posVar   = Var(seed.map(_.partOfSpeech))
  private val errorVar = Var(Option.empty[String])

  private val submitBus = new EventBus[Unit]()

  private val left  = new Side(leftLanguage, seed.flatMap(_.left))
  private val right = new Side(rightLanguage, seed.flatMap(_.right))

  private lazy val leftPicker: WordPicker  = wordPicker(left, right, () => rightPicker, UiKeys.tagsSourcePlaceholder)
  private lazy val rightPicker: WordPicker = wordPicker(right, left, () => leftPicker, UiKeys.tagsTargetPlaceholder)

  seed.foreach(s => {
    s.left.foreach(side => leftPicker.setText(Word.display(side.word)))
    s.right.foreach(side => rightPicker.setText(Word.display(side.word)))
  })

  def focus(): Unit = leftPicker.focus()

  /** Back to an empty add row, after a row was added. */
  def reset(): Unit = {
    Var.set(posVar -> None, errorVar -> None)
    left.reset(); right.reset()
    leftPicker.clear(); rightPicker.clear()
    focus()
  }

  /** One word box. Committing a word saves the row once the other box holds one too — on the add row whichever box is
    * filled last, and in edit mode the answer box, as before. Enter on an empty box saves the other word alone.
    */
  private def wordPicker(side: Side, other: Side, otherPicker: () => WordPicker, placeholderKey: String): WordPicker = {
    val answer = side eq right
    new WordPicker(
      language = side.language,
      partOfSpeech = posVar.signal,
      onCommit = Observer[TagPairWord] { ref =>
        side.wordVar.set(Some(ref))
        val complete = if (editing) answer else other.wordVar.now().isDefined
        if (complete) submitBus.emit(())
        else dom.window.setTimeout(() => otherPicker().focus(), 0)
      },
      // A dictionary pick settles the row's part of speech when nothing has yet.
      onCommitWord = Observer[Option[Word]](_.foreach(word => {
        if (posVar.now().isEmpty) posVar.set(Some(word.partOfSpeech))
      })),
      onEmptyCommit = Observer[Unit](_ => if (other.wordVar.now().isDefined) submitBus.emit(())),
      placeholderSignal = side.language.map(language => I18n.t(placeholderKey, Labels.language(language))),
      translateFrom = other.wordVar.signal.map(_.flatMap(TagEntryEditor.idOf)),
    )
  }

  /** The body to send, or `None` when there is nothing to send or the reader said no to the warning. */
  private def build(admin: Boolean): Option[TagEntryInput] = {
    val pos   = posVar.now()
    val words = (left.entry(pos), right.entry(pos))
    if (words._1.isEmpty && words._2.isEmpty) None
    else {
      val gates   = List(left, right).flatMap(_.gate)
      val unlinks = List(left, right).flatMap(_.unlinked)
      TagEntryEditor.gate(gates, unlinks, pos, admin) match {
        case Gate.Refused =>
          errorVar.set(Some(I18n.t(MessageKeys.wordDictionaryProtected)))
          None
        case Gate.Confirm =>
          Option.when(dom.window.confirm(I18n.t(UiKeys.tagsEditorDictionaryWarn)))(
            TagEntryInput(words._1, words._2, pos, confirm = true)
          )
        case Gate.Go      =>
          Some(TagEntryInput(words._1, words._2, pos))
      }
    }
  }

  def render(): HtmlElement = {
    div(
      cls := "flex flex-col gap-3",
      div(
        cls                := "grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2 sm:grid-flow-col sm:grid-rows-[auto_auto_auto]",
        dataAttr("testid") := (if (editing) "tag-edit-row" else "tag-add-row"),
        left.pickerCell(leftPicker),
        left.noteCell(),
        left.formsCell(),
        right.pickerCell(rightPicker).amend(cls := "mt-2 sm:mt-0"),
        right.noteCell(),
        right.formsCell(),
      ),
      div(
        cls                := "flex flex-wrap items-center gap-2",
        renderPartOfSpeech(),
        div(cls := "grow"),
        button(
          typ   := "button",
          cls   := "btn btn-primary btn-sm",
          disabled <-- left.wordVar.signal.combineWith(right.wordVar.signal).map { case (l, r) =>
            l.isEmpty && r.isEmpty
          },
          I18n.t(if (editing) UiKeys.tagsEditorSaveRow else UiKeys.commonAdd),
          onClick.mapToUnit --> submitBus.writer,
        ),
        Option.when(editing)(
          button(
            typ := "button",
            cls := "btn btn-ghost btn-sm",
            I18n.t(UiKeys.commonCancel),
            onClick.mapToUnit --> onCancel,
          )
        ),
      ),
      child.maybe <-- errorVar.signal.map(_.map(msg => p(cls := "text-error text-xs", msg))),
      Option.when(!editing)(p(cls := "text-xs opacity-70", I18n.t(UiKeys.tagsEditorAddWordOnlyHint))),
      submitBus.events.sample(AppState.isGlobalAdminSignal) --> Observer[Boolean] { admin =>
        errorVar.set(None)
        build(admin).foreach(onSubmit.onNext)
      },
      left.detailLoader(),
      left.relationLoader(),
      right.detailLoader(),
      right.relationLoader(),
      onMountCallback(_ => if (editing) dom.window.setTimeout(() => focus(), 0)),
    )
  }

  /** The row's part of speech. Changing it drops the forms not yet saved, since their main words have the old one. */
  private def renderPartOfSpeech(): HtmlElement = {
    label(
      cls := "flex items-center gap-2 text-sm",
      span(I18n.t(UiKeys.tagsEditorPartOfSpeech)),
      select(
        cls := "select select-sm w-36",
        Option.when(!editing)(
          option(value := TagEntryEditor.anyPartOfSpeech, I18n.t(UiKeys.tagsEditorAnyPartOfSpeech))
        ),
        PartOfSpeech.all.map(pos => option(value := PartOfSpeech.code(pos), Labels.partOfSpeech(pos))),
        controlled(
          value <-- posVar.signal.map(_.map(PartOfSpeech.code).getOrElse(TagEntryEditor.anyPartOfSpeech)),
          onChange.mapToValue --> Observer[String] { code =>
            posVar.set(PartOfSpeech.fromString(code))
            left.dropPending(); right.dropPending()
          },
        ),
      ),
    )
  }

  /** One word of the row and what goes with it. */
  private final class Side(val language: Signal[WordLanguage], seed: Option[SeedSide]) {

    val wordVar: Var[Option[TagPairWord]] = Var(seed.map(side => TagPairWord.Existing(side.word.id)))

    private val noteVar      = Var(seed.flatMap(_.note).getOrElse(""))
    private val detailVar    = Var(Option.empty[WordDetail])
    private val removedVar   = Var(Set.empty[(Long, String)])
    private val pendingVar   = Var(List.empty[PendingLink])
    private val mainRefVar   = Var(Option.empty[TagPairWord])
    private val mainLabelVar = Var("")
    private val relationsVar = Var(Option.empty[List[String]])
    private val relationVar  = Var("")

    private val mainPicker = new WordPicker(
      language = language,
      partOfSpeech = posVar.signal,
      onCommit = Observer[TagPairWord] { ref =>
        mainRefVar.set(Some(ref))
        ref match {
          case TagPairWord.New(_, text, _, _) => mainLabelVar.set(text)
          case TagPairWord.Existing(_)        => ()
        }
      },
      onCommitWord = Observer[Option[Word]](_.foreach(word => mainLabelVar.set(Word.display(word)))),
      placeholderSignal = Val(I18n.t(UiKeys.tagsEditorMainWordSearch)),
      mainOnly = true,
    )

    def reset(): Unit = {
      Var.set(
        wordVar    -> None,
        noteVar    -> "",
        detailVar  -> None,
        removedVar -> Set.empty[(Long, String)],
      )
      dropPending()
    }

    def dropPending(): Unit = {
      Var.set(pendingVar -> Nil, mainRefVar -> None, mainLabelVar -> "")
      mainPicker.clear()
    }

    /** The detail of the dictionary word in the box, when it is loaded and still the one in the box. */
    private def detail: Option[WordDetail] = {
      detailVar.now().filter(d => wordVar.now().flatMap(TagEntryEditor.idOf).contains(d.word.id))
    }

    def gate: Option[WordGate] = detail.map(d => WordGate(d.word.partOfSpeech, d.fromDictionary, d.createdByMe))

    def unlinked: List[WordFormRef] = {
      detail.toList.flatMap(_.mainWords.filter(ref => removedVar.now().contains((ref.word.id, ref.relation))))
    }

    /** A main word picked and given a form type counts even before its Add is pressed: Save should not drop it. */
    private def links: List[PendingLink] = {
      val picked = mainRefVar.now().filter(_ => relationVar.now().nonEmpty).map { ref =>
        PendingLink(ref, mainLabelVar.now(), relationVar.now())
      }
      pendingVar.now() ++ picked
    }

    def entry(pos: Option[PartOfSpeech]): Option[TagEntryWord] = {
      wordVar.now().map { ref =>
        TagEntryWord(
          TagEntryEditor.withPartOfSpeech(ref, pos),
          Option(noteVar.now().trim).filter(_.nonEmpty),
          links.map(link => MainWordLink(TagEntryEditor.withPartOfSpeech(link.mainWord, pos), link.relation)),
          unlinked.map(ref => MainWordUnlink(ref.word.id, ref.relation)),
        )
      }
    }

    /** The detail of the dictionary word in the box: its links, and who made it. */
    def detailLoader(): Modifier[HtmlElement] = {
      wordVar.signal.map(_.flatMap(TagEntryEditor.idOf)).distinct.flatMapSwitch {
        case Some(id) => WordApiClient.get(id).map(_.toOption).startWith(None)
        case None     => Val(Option.empty[WordDetail])
      } --> Observer[Option[WordDetail]](detail =>
        Var.set(detailVar -> detail, removedVar -> Set.empty[(Long, String)])
      )
    }

    /** The form types follow the row's part of speech, which a main word shares. */
    def relationLoader(): Modifier[HtmlElement] = {
      language.combineWith(posVar.signal).flatMapSwitch {
        case (lang, Some(pos)) =>
          WordApiClient.formRelations(lang, pos).map(result => Some(result.getOrElse(Nil))).startWith(None)
        case (_, None)         =>
          Val(Option.empty[List[String]])
      } --> Observer[Option[List[String]]] { relations =>
        Var.set(relationsVar -> relations, relationVar -> relations.flatMap(_.headOption).getOrElse(""))
      }
    }

    def pickerCell(picker: WordPicker): HtmlElement = div(cls := "self-end min-w-0", picker.render())

    def noteCell(): HtmlElement = {
      label(
        cls := "input input-sm w-full",
        span(cls      := "label", I18n.t(UiKeys.tagsEditorNoteLabel)),
        input(
          typ         := "text",
          maxLength   := 255,
          placeholder := I18n.t(UiKeys.tagsEditorNotePlaceholder),
          controlled(value <-- noteVar.signal, onInput.mapToValue --> noteVar.writer),
          // Enter saves the row, as it does in a word box.
          onKeyDown.filter(_.key == "Enter").preventDefault.mapToUnit --> submitBus.writer,
        ),
      )
    }

    /** The main words this word is a form of, and the box that adds one: a main word first, then a form type. A saved
      * link offers its remove button only to whoever may remove it; a link not yet saved can always be dropped.
      */
    def formsCell(): HtmlElement = {
      div(
        cls := "flex flex-col gap-1 min-w-0",
        span(cls := "label-text text-xs opacity-70", I18n.t(UiKeys.tagsEditorFormOfLabel)),
        child <-- Signal
          .combine(detailVar.signal, removedVar.signal, pendingVar.signal, AppState.isGlobalAdminSignal)
          .map { case (detail, removed, pending, admin) =>
            val saved =
              detail.toList.flatMap(_.mainWords).filterNot(ref => removed.contains((ref.word.id, ref.relation)))
            if (saved.isEmpty && pending.isEmpty)
              p(cls := "text-xs opacity-60", if (detail.isDefined) I18n.t(UiKeys.tagsEditorNotAForm) else "")
            else {
              ul(
                cls := "flex flex-col gap-0.5 text-sm",
                saved.map(ref => {
                  linkItem(
                    Word.display(ref.word),
                    ref.relation,
                    Option.when((ref.createdByMe && !ref.fromDictionary) || admin)(() =>
                      removedVar.update(_ + ((ref.word.id, ref.relation)))
                    ),
                  )
                }),
                pending.map(link => {
                  linkItem(link.label, link.relation, Some(() => pendingVar.update(_.filterNot(_ == link))))
                }),
              )
            }
          },
        child <-- posVar.signal.map(_.isDefined).distinct.map {
          case false => p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorFormNeedsPartOfSpeech))
          case true  => div(cls := "flex flex-col gap-1", mainPicker.render(), child.maybe <-- renderRelation())
        },
      )
    }

    private def linkItem(label: String, relation: String, remove: Option[() => Unit]): HtmlElement = {
      li(
        cls := "flex flex-wrap items-center gap-2",
        span(label),
        span(cls := "text-xs opacity-60", Labels.grammarRelation(relation)),
        remove.map(action => {
          button(
            typ := "button",
            cls := "btn btn-ghost btn-xs",
            I18n.t(UiKeys.tagsEditorRemoveForm),
            onClick.mapToUnit --> Observer[Unit](_ => action()),
          )
        }),
      )
    }

    /** The form type of the main word just picked, and Add to put another main word after it. */
    private def renderRelation(): Signal[Option[HtmlElement]] = {
      mainRefVar.signal.combineWith(relationsVar.signal).map {
        case (None, _)                                       =>
          None
        case (Some(_), None)                                 =>
          Some(span(cls := "loading loading-spinner loading-xs", role := "status"))
        case (Some(_), Some(relations)) if relations.isEmpty =>
          Some(p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorNoRelations)))
        case (Some(ref), Some(relations))                    =>
          Some(
            div(
              cls := "flex flex-wrap gap-2",
              select(
                cls        := "select select-sm flex-1 min-w-0",
                aria.label := I18n.t(UiKeys.tagsEditorFormRelation),
                relations.map(relation => option(value := relation, Labels.grammarRelation(relation))),
                controlled(value <-- relationVar.signal, onChange.mapToValue --> relationVar.writer),
              ),
              button(
                typ        := "button",
                cls        := "btn btn-sm",
                I18n.t(UiKeys.tagsEditorAddForm),
                onClick.mapToUnit --> Observer[Unit] { _ =>
                  val link = PendingLink(ref, mainLabelVar.now(), relationVar.now())
                  pendingVar.update(links => if (links.contains(link)) links else links :+ link)
                  Var.set(mainRefVar -> None, mainLabelVar -> "")
                  mainPicker.clear()
                },
              ),
            )
          )
      }
    }
  }
}

private[pages] object TagEntryEditor {

  /** A word of a row that is being edited, with the note beside it. */
  final case class SeedSide(word: Word, note: Option[String])

  /** The row an edit starts from: its words in the columns' order, and its part of speech. */
  final case class Seed(left: Option[SeedSide], right: Option[SeedSide], partOfSpeech: PartOfSpeech)

  /** What decides whether the reader may give a word another part of speech. */
  final case class WordGate(partOfSpeech: PartOfSpeech, fromDictionary: Boolean, mine: Boolean)

  enum Gate {
    case Go, Confirm, Refused
  }

  /** Whether a save may go, needs the warning first, or is not the reader's to make — the server's rule, asked before
    * the request so the reader is not sent a refusal. A word takes the row's part of speech only when it has another.
    * Only an administrator sees a remove button on a link that is not theirs, so an unlink needs the warning only.
    */
  def gate(words: List[WordGate], unlinks: List[WordFormRef], pos: Option[PartOfSpeech], admin: Boolean): Gate = {
    val retyped    = pos.toList.flatMap(p => words.filter(_.partOfSpeech != p))
    val dictionary = retyped.exists(_.fromDictionary) || unlinks.exists(_.fromDictionary)
    if (!admin && retyped.exists(word => !word.mine || word.fromDictionary)) Gate.Refused
    else if (admin && dictionary) Gate.Confirm
    else Gate.Go
  }

  /** The add row's "any part of speech" option. An empty value is dropped from the markup, so it has a word of its own,
    * which no part of speech's code is.
    */
  val anyPartOfSpeech = "any"

  /** A main word picked for a form, with what the list shows for it before it is saved. */
  private final case class PendingLink(mainWord: TagPairWord, label: String, relation: String)

  private def idOf(ref: TagPairWord): Option[Long] = ref match {
    case TagPairWord.Existing(id)    => Some(id)
    case TagPairWord.New(_, _, _, _) => None
  }

  /** A word to create takes the row's part of speech; a dictionary word is given it by the server. */
  private def withPartOfSpeech(ref: TagPairWord, pos: Option[PartOfSpeech]): TagPairWord = ref match {
    case fresh: TagPairWord.New => pos.fold(fresh)(p => fresh.copy(partOfSpeech = p))
    case existing               => existing
  }
}
