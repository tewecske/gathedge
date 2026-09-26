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
  * It holds the two words of the row, the row's part of speech, the reader's note beside each word, and the main word
  * each word is a form of. An import writes the notes and the forms from the file. Here the reader writes them by hand,
  * so a word typed in can carry them too. Save sends all of it in one request: `onSubmit` gets the body, and the page
  * sends it to the add or the edit endpoint.
  *
  * '''The part of speech is the row's.''' Both word boxes and both main-word boxes search it, a new word is created
  * with it, and the form types on offer are the ones the dictionary's own forms carry for it, so a noun is never
  * offered `past`. A word that has another part of speech is given it on save. On the add row it starts empty and the
  * first dictionary word picked sets it. The form types are per word, since each language's forms have their own types.
  *
  * '''A word is a form of one main word at most.''' A word that already has one shows it, with a remove button, and the
  * main-word box appears once it is removed. The form type is mandatory, so its select shows with the box.
  *
  * '''Shared data is guarded.''' A word the reader did not make is an administrator's to change, and so is a link the
  * reader did not make, so the editor offers no control for them: the part of speech is locked and a link has no remove
  * button. An administrator confirms a warning before changing dictionary data. The server applies the same rule.
  *
  * The grid keeps the two word boxes on one line on a wide screen: a German box has its article buttons above it, so
  * the boxes sit at the bottom of their shared row. The notes, the part of speech and the forms follow in rows of their
  * own. On a phone each word keeps its note and form together, under the part of speech.
  */
private[pages] final class TagEntryEditor(
  leftLanguage: Signal[WordLanguage],
  rightLanguage: Signal[WordLanguage],
  seed: Option[TagEntryEditor.Seed],
  onSubmit: Observer[TagEntryInput],
  onCancel: Observer[Unit] = Observer.empty,
) {

  import TagEntryEditor.{Gate, SeedSide, WordGate}

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
      translateFrom = other.detailSignal,
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
      // Placed by hand from `sm` up, so the part of speech can span both columns between the notes and the forms. Below
      // `sm` the source order holds: the part of speech first, then each word with its note and form.
      div(
        cls                := "grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2",
        dataAttr("testid") := (if (editing) "tag-edit-row" else "tag-add-row"),
        renderPartOfSpeech().amend(cls          := "sm:col-span-2 sm:row-start-3"),
        left.pickerCell(leftPicker).amend(cls   := "sm:col-start-1 sm:row-start-1"),
        left.noteCell().amend(cls               := "sm:col-start-1 sm:row-start-2"),
        left.formsCell().amend(cls              := "sm:col-start-1 sm:row-start-4"),
        right.pickerCell(rightPicker).amend(cls := "mt-2 sm:mt-0 sm:col-start-2 sm:row-start-1"),
        right.noteCell().amend(cls              := "sm:col-start-2 sm:row-start-2"),
        right.formsCell().amend(cls             := "sm:col-start-2 sm:row-start-4"),
      ),
      div(
        cls                := "flex flex-wrap items-center justify-end gap-2",
        button(
          typ := "button",
          cls := "btn btn-primary btn-sm",
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

  /** The row's part of speech. It is locked when a word of the row is not the reader's to change, since the change
    * would be refused; an administrator may change any, after the warning. Changing it drops a main word picked and not
    * saved, since that main word has the old one.
    */
  private def renderPartOfSpeech(): HtmlElement = {
    val locked = Signal
      .combine(left.lockedSignal, right.lockedSignal, AppState.isGlobalAdminSignal)
      .map { case (l, r, admin) => (l || r) && !admin }
    label(
      cls := "flex items-center gap-2 text-sm",
      span(I18n.t(UiKeys.tagsEditorPartOfSpeech)),
      select(
        cls := "select select-sm w-36",
        disabled <-- locked,
        Option.when(!editing)(
          option(value := TagEntryEditor.anyPartOfSpeech, I18n.t(UiKeys.tagsEditorAnyPartOfSpeech))
        ),
        PartOfSpeech.all.map(pos => option(value := PartOfSpeech.code(pos), Labels.partOfSpeech(pos))),
        controlled(
          value <-- posVar.signal.map(_.map(PartOfSpeech.code).getOrElse(TagEntryEditor.anyPartOfSpeech)),
          onChange.mapToValue --> Observer[String] { code =>
            posVar.set(PartOfSpeech.fromString(code))
            left.clearMainWord(); right.clearMainWord()
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
    private val mainRefVar   = Var(Option.empty[TagPairWord])
    private val relationsVar = Var(Option.empty[List[String]])
    private val relationVar  = Var("")

    private val mainPicker = new WordPicker(
      language = language,
      partOfSpeech = posVar.signal,
      onCommit = Observer[TagPairWord](ref => mainRefVar.set(Some(ref))),
      placeholderSignal = Val(I18n.t(UiKeys.tagsEditorMainWordSearch)),
      mainOnly = true,
    )

    /** Built once, so the box keeps what is typed in it while the block around it is redrawn. */
    private lazy val mainBox: HtmlElement = div(cls := "flex flex-col gap-1", mainPicker.render(), renderRelation())

    def reset(): Unit = {
      Var.set(
        wordVar    -> None,
        noteVar    -> "",
        detailVar  -> None,
        removedVar -> Set.empty[(Long, String)],
      )
      clearMainWord()
    }

    def clearMainWord(): Unit = {
      mainRefVar.set(None)
      mainPicker.clear()
    }

    /** The detail of the dictionary word in the box, when it is loaded and still the one in the box. */
    private def detailOf(word: Option[TagPairWord], detail: Option[WordDetail]): Option[WordDetail] = {
      detail.filter(d => word.flatMap(TagEntryEditor.idOf).contains(d.word.id))
    }

    /** What decides whether the reader may change the word in the box: the row's own flags for the word it opened with,
      * and the word's detail for one picked since.
      */
    private def gateOf(word: Option[TagPairWord], detail: Option[WordDetail]): Option[WordGate] = {
      word.flatMap(TagEntryEditor.idOf).flatMap { id =>
        seed
          .filter(_.word.id == id)
          .map(s => WordGate(s.word.partOfSpeech, s.fromDictionary, s.mine))
          .orElse(detailOf(word, detail).map(d => WordGate(d.word.partOfSpeech, d.fromDictionary, d.createdByMe)))
      }
    }

    def gate: Option[WordGate] = gateOf(wordVar.now(), detailVar.now())

    /** The detail of the word in the box, once read — also what the other box offers this word's translations from. */
    val detailSignal: Signal[Option[WordDetail]] = {
      wordVar.signal.combineWith(detailVar.signal).map { case (word, detail) => detailOf(word, detail) }.distinct
    }

    /** The word in the box is not the reader's to change. A new word is theirs; a word not yet read is not locked, and
      * the server still decides.
      */
    val lockedSignal: Signal[Boolean] = {
      wordVar.signal.combineWith(detailVar.signal).map { case (word, detail) =>
        gateOf(word, detail).exists(g => g.fromDictionary || !g.mine)
      }
    }

    def unlinked: List[WordFormRef] = {
      detailOf(wordVar.now(), detailVar.now()).toList.flatMap(
        _.mainWords.filter(ref => removedVar.now().contains((ref.word.id, ref.relation)))
      )
    }

    def entry(pos: Option[PartOfSpeech]): Option[TagEntryWord] = {
      val link = mainRefVar.now().filter(_ => relationVar.now().nonEmpty).map { ref =>
        MainWordLink(TagEntryEditor.withPartOfSpeech(ref, pos), relationVar.now())
      }
      wordVar.now().map { ref =>
        TagEntryWord(
          TagEntryEditor.withPartOfSpeech(ref, pos),
          Option(noteVar.now().trim).filter(_.nonEmpty),
          link,
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

    /** The main word this word is a form of. A saved one is listed, with a remove button only for whoever may remove
      * it; while one is kept there is no box, since a word is a form of one main word. Otherwise the main-word box and
      * the form type, which is mandatory. A dictionary word is read before any of it shows.
      */
    def formsCell(): HtmlElement = {
      div(
        cls := "flex flex-col gap-1 min-w-0",
        span(cls := "label-text text-xs opacity-70", I18n.t(UiKeys.tagsEditorFormOfLabel)),
        child <-- Signal
          .combine(wordVar.signal, detailVar.signal, removedVar.signal, posVar.signal, AppState.isGlobalAdminSignal)
          .map { case (word, detail, removed, pos, admin) =>
            val loading = word.flatMap(TagEntryEditor.idOf).isDefined && detailOf(word, detail).isEmpty
            val kept    = detailOf(word, detail).toList
              .flatMap(_.mainWords)
              .filterNot(ref => removed.contains((ref.word.id, ref.relation)))
            if (pos.isEmpty) p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorFormNeedsPartOfSpeech))
            else if (loading) span(cls := "loading loading-spinner loading-xs", role := "status")
            else if (kept.nonEmpty) {
              ul(
                cls := "flex flex-col gap-0.5 text-sm",
                kept.map(ref => {
                  linkItem(
                    ref,
                    Option.when((ref.createdByMe && !ref.fromDictionary) || admin)(() =>
                      removedVar.update(_ + ((ref.word.id, ref.relation)))
                    ),
                  )
                }),
              )
            } else mainBox
          },
      )
    }

    private def linkItem(ref: WordFormRef, remove: Option[() => Unit]): HtmlElement = {
      li(
        cls := "flex flex-wrap items-center gap-2",
        span(Word.display(ref.word)),
        span(cls := "text-xs opacity-60", Labels.grammarRelation(ref.relation)),
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

    /** The form type: always shown beside the main-word box, since a link needs one. */
    private def renderRelation(): HtmlElement = {
      div(
        child <-- relationsVar.signal.map {
          case None                                 => span(cls := "loading loading-spinner loading-xs", role := "status")
          case Some(relations) if relations.isEmpty =>
            p(cls := "text-xs opacity-60", I18n.t(UiKeys.tagsEditorNoRelations))
          case Some(relations)                      =>
            select(
              cls        := "select select-sm w-full",
              aria.label := I18n.t(UiKeys.tagsEditorFormRelation),
              relations.map(relation => option(value := relation, Labels.grammarRelation(relation))),
              controlled(value <-- relationVar.signal, onChange.mapToValue --> relationVar.writer),
            )
        }
      )
    }
  }
}

private[pages] object TagEntryEditor {

  /** A word of a row that is being edited, with the note beside it, and whether the import wrote it and whether it is
    * the reader's own — what locks its part of speech, read off the row with no request.
    */
  final case class SeedSide(word: Word, note: Option[String], fromDictionary: Boolean = false, mine: Boolean = true)

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
