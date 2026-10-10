package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.{AppRouter, Page}
import gathedge.frontend.api.{ApiError, WordApiClient}
import gathedge.frontend.components.{Alert, AppShell, Labels}
import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.{
  DrillBoard,
  DrillCell,
  DrillRound,
  DrillTableRef,
  FormDrill,
  FormPrefix,
  FormTemplates,
  LanguageProfile,
  PartOfSpeech,
  Tag,
  Word,
  WordLanguage,
}
import gathedge.shared.dto.{FormDrillWord, WordFormEntry}
import gathedge.shared.i18n.UiKeys

import scala.util.Random

/** The form drill (`/tags/{id}/forms`): a wordlist's nouns or verbs, one form table at a time, with the cells empty.
  * The player puts each form back into its cell, by dragging a chip or by tapping a chip and then a cell.
  *
  * Nothing is stored. The page reads the wordlist's words with their forms once per language and part of speech (`GET
  * /api/tags/{tagId}/forms`), builds each round with `FormDrill.round`, and checks the answers itself.
  */
object FormDrillPage {

  def render(tagId: Long): HtmlElement = {
    AppShell.render(Page.FormDrill(tagId), new FormDrillPage(tagId).render())
  }

  /** The parts of speech the drill offers. */
  val partsOfSpeech: List[PartOfSpeech] = List(PartOfSpeech.Verb, PartOfSpeech.Noun)

  /** How many words a session may hold. `None` is every word. */
  val wordCounts: List[Option[Int]] = List(Some(5), Some(10), Some(20), None)

  /** The tag's languages that have a form template for `pos`, source language first. */
  def languagesFor(tag: Tag, pos: PartOfSpeech): List[WordLanguage] = {
    List(tag.sourceLanguage, tag.targetLanguage).distinct.filter(FormTemplates.of(_, pos).isDefined)
  }

  /** The round each word gives on the table `ref` names, leaving out a word that gives none. */
  def roundsFor(words: List[FormDrillWord], ref: DrillTableRef): List[(Word, DrillRound)] = {
    words.flatMap(entry => {
      val word    = entry.word
      val profile = LanguageProfile.of(word.language)
      for {
        template <- FormTemplates.of(word.language, word.partOfSpeech)
        round    <- FormDrill.round(
                      template,
                      ref,
                      entry.forms,
                      word.text,
                      (kind: FormPrefix, tags: Set[String]) => profile.prefix(kind, word.gender, tags, word.text),
                    )(_.relation, (form: WordFormEntry) => form.word.id, _.word.text)
      } yield (word, round)
    })
  }

  /** A table's name in the picker: its section's title and its own, whichever it has. */
  def tableName(ref: DrillTableRef): String = {
    (ref.sectionTitle.toList ++ ref.title.toList).map(Labels.formLabel) match {
      case Nil   => I18n.t(UiKeys.formDrillTableUntitled)
      case parts => parts.mkString(" · ")
    }
  }
}

/** One session: the rounds in play order, the one on screen, and its board. `score` and `maxScore` count the gaps of
  * the rounds already finished.
  */
private final case class DrillSession(
  rounds: List[(Word, DrillRound)],
  current: Int,
  board: DrillBoard,
  score: Int,
  maxScore: Int,
) {

  def word: Word = rounds(current)._1

  def round: DrillRound = rounds(current)._2

  def isLast: Boolean = current == rounds.size - 1
}

private object DrillSession {

  def start(rounds: List[(Word, DrillRound)]): DrillSession = {
    DrillSession(rounds, 0, DrillBoard.of(rounds.head._2, Random.shuffle(_)), 0, 0)
  }
}

private enum DrillPhase {
  case Setup
  case Playing(session: DrillSession)
  case Done(score: Int, maxScore: Int)
}

private class FormDrillPage(tagId: Long) {

  import FormDrillPage.*

  private val tagVar: Var[Option[Either[String, Tag]]]                        = Var(None)
  private val posVar: Var[PartOfSpeech]                                       = Var(PartOfSpeech.Verb)
  private val languageVar: Var[Option[WordLanguage]]                          = Var(None)
  private val wordsVar: Var[Option[Either[String, List[FormDrillWord]]]]      = Var(None)
  private val tableVar: Var[Option[DrillTableRef]]                            = Var(None)
  private val countVar: Var[Option[Int]]                                      = Var(Some(10))
  private val phaseVar: Var[DrillPhase]                                       = Var(DrillPhase.Setup)
  private val selectedVar: Var[Option[Int]]                                   = Var(None)
  private val loadBus: EventBus[(WordLanguage, PartOfSpeech)]                 = new EventBus()
  private val tagSignal: Signal[Option[Tag]]                                  = tagVar.signal.map(_.flatMap(_.toOption))
  private val choiceSignal: Signal[Option[(Tag, WordLanguage, PartOfSpeech)]] = {
    tagSignal.combineWith(languageVar.signal, posVar.signal).map {
      case (Some(tag), Some(language), pos) => Some((tag, language, pos))
      case _                                => None
    }
  }

  /** Every table of the chosen template, with the rounds it gives over the loaded words. Tables that give none are left
    * out, so the picker never offers an empty drill.
    */
  private val tablesSignal: Signal[List[(DrillTableRef, List[(Word, DrillRound)])]] = {
    choiceSignal.combineWith(wordsVar.signal).map {
      case (Some((_, language, pos)), Some(Right(words))) =>
        FormTemplates
          .of(language, pos)
          .toList
          .flatMap(FormDrill.tables)
          .map(ref => (ref, roundsFor(words, ref)))
          .filter { case (_, rounds) => rounds.nonEmpty }
      case _                                              =>
        Nil
    }
  }

  private val chosenSignal: Signal[Option[(DrillTableRef, List[(Word, DrillRound)])]] = {
    tablesSignal.combineWith(tableVar.signal).map { case (tables, chosen) =>
      chosen.flatMap(ref => tables.find { case (candidate, _) => candidate == ref }).orElse(tables.headOption)
    }
  }

  def render(): HtmlElement = {
    div(
      cls := "p-4 max-w-4xl mx-auto flex flex-col gap-4",
      WordApiClient.getTag(tagId) --> Observer[Either[ApiError, Tag]](result => {
        tagVar.set(Some(result.left.map(_.message)))
        result.foreach(tag => pickLanguage(tag, posVar.now()))
      }),
      loadBus.events.flatMapSwitch { case (language, pos) =>
        WordApiClient.tagForms(tagId, language, pos)
      } --> Observer[Either[ApiError, List[FormDrillWord]]](result => {
        wordsVar.set(Some(result.left.map(_.message)))
      }),
      child <-- tagVar.signal.map {
        case None              => span(cls := "loading loading-spinner")
        case Some(Left(error)) => Alert.error(error)
        case Some(Right(tag))  => renderPage(tag)
      },
    )
  }

  /** Points the language at the first one `pos` has a template for, and loads its words. */
  private def pickLanguage(tag: Tag, pos: PartOfSpeech): Unit = {
    val language = languageVar.now().filter(languagesFor(tag, pos).contains).orElse(languagesFor(tag, pos).headOption)
    Var.set(languageVar -> language, tableVar -> None, wordsVar -> None)
    language.foreach(l => loadBus.emit((l, pos)))
  }

  private def renderPage(tag: Tag): HtmlElement = {
    div(
      cls := "flex flex-col gap-4",
      div(
        h1(cls := "text-2xl font-bold", I18n.t(UiKeys.formDrillTitle)),
        a(
          cls  := "link link-hover text-sm opacity-70",
          AppRouter.router.navigateTo(Page.TagDetail(tag.id)),
          tag.name,
        ),
      ),
      // Keyed on the phase alone, so a chip move redraws the board inside `renderPlaying`, not the whole view.
      child <-- phaseVar.signal
        .map {
          case DrillPhase.Playing(_) => None
          case other                 => Some(other)
        }
        .distinct
        .map {
          case None                                   => renderPlaying()
          case Some(DrillPhase.Done(score, maxScore)) => renderDone(tag, score, maxScore)
          case Some(_)                                => renderSetup(tag)
        },
    )
  }

  // -- Setup -------------------------------------------------------------------------------------

  private def renderSetup(tag: Tag): HtmlElement = {
    div(
      cls := "flex flex-col gap-4",
      p(cls := "text-sm opacity-70", I18n.t(UiKeys.formDrillIntro)),
      div(
        cls := "flex flex-wrap items-end gap-3",
        renderPosSelect(tag),
        child.maybe <-- posVar.signal.map(pos => {
          val languages = languagesFor(tag, pos)
          Option.when(languages.size > 1)(renderLanguageSelect(tag, languages))
        }),
        child.maybe <-- tablesSignal.map(tables => Option.when(tables.nonEmpty)(renderTableSelect(tables))),
        child.maybe <-- tablesSignal.map(tables => Option.when(tables.nonEmpty)(renderCountSelect())),
      ),
      child <-- posVar.signal.combineWith(languageVar.signal, wordsVar.signal, chosenSignal).map {
        case (_, None, _, _)                           =>
          Alert.info(I18n.t(UiKeys.formDrillNoTemplate))
        case (_, _, None, _)                           =>
          span(cls := "loading loading-spinner")
        case (_, _, Some(Left(error)), _)              =>
          Alert.error(error)
        case (_, _, Some(Right(_)), None)              =>
          Alert.info(I18n.t(UiKeys.formDrillNoWords))
        case (_, _, Some(Right(_)), Some((_, rounds))) =>
          div(
            button(
              typ := "button",
              cls := "btn btn-primary",
              I18n.t(UiKeys.formDrillStart),
              onClick.mapToUnit --> Observer[Unit](_ => start(rounds)),
            )
          )
      },
    )
  }

  private def labelled(title: String, control: HtmlElement): HtmlElement = {
    label(cls := "form-control", div(cls := "label-text text-xs mb-1", title), control)
  }

  private def renderPosSelect(tag: Tag): HtmlElement = {
    labelled(
      I18n.t(UiKeys.formDrillPartOfSpeech),
      select(
        cls := "select select-sm",
        partsOfSpeech.map(pos => option(value := PartOfSpeech.code(pos), Labels.partOfSpeech(pos))),
        controlled(
          value <-- posVar.signal.map(PartOfSpeech.code),
          onChange.mapToValue --> Observer[String](code => {
            PartOfSpeech
              .fromString(code)
              .foreach(pos => {
                posVar.set(pos)
                pickLanguage(tag, pos)
              })
          }),
        ),
      ),
    )
  }

  private def renderLanguageSelect(tag: Tag, languages: List[WordLanguage]): HtmlElement = {
    labelled(
      I18n.t(UiKeys.formDrillLanguage),
      select(
        cls := "select select-sm",
        languages.map(l => option(value := WordLanguage.code(l), Labels.language(l))),
        controlled(
          value <-- languageVar.signal.map(_.map(WordLanguage.code).getOrElse("")),
          onChange.mapToValue --> Observer[String](code => {
            WordLanguage
              .fromString(code)
              .foreach(language => {
                Var.set(languageVar -> Some(language), tableVar -> None, wordsVar -> None)
                loadBus.emit((language, posVar.now()))
              })
          }),
        ),
      ),
    )
  }

  private def renderTableSelect(tables: List[(DrillTableRef, List[(Word, DrillRound)])]): HtmlElement = {
    val key = (ref: DrillTableRef) => s"${ref.section}-${ref.table}"
    labelled(
      I18n.t(UiKeys.formDrillTable),
      select(
        cls := "select select-sm",
        tables.map { case (ref, rounds) =>
          option(value := key(ref), s"${tableName(ref)} (${rounds.size})")
        },
        controlled(
          value <-- chosenSignal.map(_.map { case (ref, _) => key(ref) }.getOrElse("")),
          onChange.mapToValue --> Observer[String](code => {
            tableVar.set(tables.collectFirst { case (ref, _) if key(ref) == code => ref })
          }),
        ),
      ),
    )
  }

  private def renderCountSelect(): HtmlElement = {
    val code = (count: Option[Int]) => count.fold("all")(_.toString)
    labelled(
      I18n.t(UiKeys.formDrillWordCount),
      select(
        cls := "select select-sm",
        wordCounts.map(count => option(value := code(count), count.fold(I18n.t(UiKeys.formDrillAllWords))(_.toString))),
        controlled(
          value <-- countVar.signal.map(code),
          onChange.mapToValue --> Observer[String](value => countVar.set(value.toIntOption)),
        ),
      ),
    )
  }

  private def start(rounds: List[(Word, DrillRound)]): Unit = {
    val picked = countVar.now().fold(Random.shuffle(rounds))(n => Random.shuffle(rounds).take(n))
    if (picked.nonEmpty) {
      Var.set(phaseVar -> DrillPhase.Playing(DrillSession.start(picked)), selectedVar -> None)
    }
  }

  // -- Playing -----------------------------------------------------------------------------------

  private val sessionSignal: Signal[Option[DrillSession]] = phaseVar.signal.map {
    case DrillPhase.Playing(session) => Some(session)
    case _                           => None
  }

  private def updateBoard(change: DrillBoard => DrillBoard): Unit = {
    phaseVar.update {
      case DrillPhase.Playing(session) => DrillPhase.Playing(session.copy(board = change(session.board)))
      case other                       => other
    }
  }

  /** A tap on a gap: places the selected chip there, or, with none selected, sends the gap's chip back to the bank. */
  private def tapGap(gap: Int): Unit = {
    selectedVar.now() match {
      case Some(chip) =>
        updateBoard(_.place(chip, gap))
        selectedVar.set(None)
      case None       =>
        updateBoard(_.clear(gap))
    }
  }

  private def tapChip(chip: Int): Unit = {
    selectedVar.update(selected => if (selected.contains(chip)) None else Some(chip))
  }

  private def next(): Unit = {
    phaseVar.update {
      case DrillPhase.Playing(session) =>
        val score    = session.score + session.board.firstTry.getOrElse(0)
        val maxScore = session.maxScore + session.board.gaps.size
        if (session.isLast)
          DrillPhase.Done(score, maxScore)
        else {
          val index = session.current + 1
          DrillPhase.Playing(
            session.copy(
              current = index,
              board = DrillBoard.of(session.rounds(index)._2, Random.shuffle(_)),
              score = score,
              maxScore = maxScore,
            )
          )
        }
      case other                       =>
        other
    }
    selectedVar.set(None)
  }

  private def renderPlaying(): HtmlElement = {
    div(
      cls := "flex flex-col gap-4",
      child.maybe <-- sessionSignal.map(_.map(session => {
        div(
          cls := "flex flex-wrap items-baseline gap-3",
          span(
            cls  := "text-sm opacity-70",
            I18n.t(UiKeys.formDrillProgress, session.current + 1, session.rounds.size),
          ),
          h2(cls := "text-xl font-semibold", Word.display(session.word)),
        )
      })),
      child.maybe <-- sessionSignal.combineWith(selectedVar.signal).map { case (session, selected) =>
        session.map(s => renderBoard(s, selected))
      },
    )
  }

  private def renderBoard(session: DrillSession, selected: Option[Int]): HtmlElement = {
    val board   = session.board
    val round   = session.round
    val word    = session.word
    val profile = LanguageProfile.of(word.language)
    val lead    = (prefix: Option[String], text: String) => {
      prefix.map(p => span(cls := "opacity-60", profile.lead(p, text, word.text)))
    }
    val headed  = round.columns.exists(_.nonEmpty)
    val titled  = round.rows.exists(_.label.nonEmpty)

    def gapCell(gap: Int, answer: String, prefix: Option[String]): HtmlElement = {
      val chip  = board.placed.get(gap)
      val state = {
        if (board.locked.contains(gap)) "border-success bg-success/10"
        else if (board.wrong.contains(gap)) "border-error bg-error/10"
        else if (chip.isDefined) "border-base-content/40"
        else "border-dashed border-base-content/30"
      }
      span(
        cls := "inline-flex items-center gap-1",
        lead(prefix, answer),
        button(
          typ             := "button",
          cls             := s"min-w-24 min-h-8 px-2 rounded border-2 text-left $state",
          dataAttr("gap") := gap.toString,
          chip.map(board.chips(_)).getOrElse(""),
          chip.filterNot(_ => board.locked.contains(gap)).map(c => draggable := true),
          chip.map(c =>
            onDragStart --> Observer[org.scalajs.dom.DragEvent](_.dataTransfer.setData("text/plain", c.toString))
          ),
          onDragOver.preventDefault --> Observer[org.scalajs.dom.DragEvent](_ => ()),
          onDrop.preventDefault --> Observer[org.scalajs.dom.DragEvent](event => {
            event.dataTransfer
              .getData("text/plain")
              .toIntOption
              .foreach(c => {
                updateBoard(_.place(c, gap))
                selectedVar.set(None)
              })
          }),
          onClick.mapToUnit --> Observer[Unit](_ => tapGap(gap)),
        ),
      )
    }

    div(
      cls := "flex flex-col gap-4",
      div(
        cls := "overflow-x-auto",
        table(
          cls := "table table-sm w-auto",
          Option.when(headed)(
            thead(
              tr(
                Option.when(titled)(th()),
                round.columns.map(column => th(column.map(Labels.formLabel).getOrElse(""))),
              )
            )
          ),
          tbody(
            round.rows.map(row => {
              tr(
                Option.when(titled)(th(cls := "font-normal opacity-70", row.label.map(Labels.formLabel).getOrElse(""))),
                row.cells.map {
                  case DrillCell.Given(text, prefix)        => td(span(lead(prefix, text), span(text)))
                  case DrillCell.Gap(index, answer, prefix) => td(gapCell(index, answer, prefix))
                  case DrillCell.Blank                      => td(span(cls := "opacity-40", "—"))
                },
              )
            })
          ),
        ),
      ),
      div(
        cls := "flex flex-wrap gap-2 min-h-10 p-2 rounded-box bg-base-200",
        board.bank.map(chip => {
          button(
            typ       := "button",
            cls       := (if (selected.contains(chip)) "btn btn-sm btn-primary" else "btn btn-sm btn-outline"),
            draggable := true,
            board.chips(chip),
            onDragStart --> Observer[org.scalajs.dom.DragEvent](_.dataTransfer.setData("text/plain", chip.toString)),
            onClick.mapToUnit --> Observer[Unit](_ => tapChip(chip)),
          )
        }),
      ),
      p(cls := "text-xs opacity-60", I18n.t(UiKeys.formDrillHowTo)),
      Option.when(board.wrong.nonEmpty)(Alert.warning(I18n.t(UiKeys.formDrillWrong))),
      div(
        cls := "flex gap-2",
        if (board.isSolved) {
          button(
            typ := "button",
            cls := "btn btn-primary",
            I18n.t(if (session.isLast) UiKeys.formDrillFinish else UiKeys.formDrillNext),
            onClick.mapToUnit --> Observer[Unit](_ => next()),
          )
        } else {
          button(
            typ      := "button",
            cls      := "btn btn-primary",
            disabled := !board.isFull,
            I18n.t(UiKeys.formDrillCheck),
            onClick.mapToUnit --> Observer[Unit](_ => updateBoard(_.check)),
          )
        },
      ),
    )
  }

  // -- Done --------------------------------------------------------------------------------------

  private def renderDone(tag: Tag, score: Int, maxScore: Int): HtmlElement = {
    div(
      cls := "flex flex-col gap-4",
      Alert.success(I18n.t(UiKeys.formDrillScore, score, maxScore)),
      div(
        cls := "flex flex-wrap gap-2",
        button(
          typ := "button",
          cls := "btn btn-primary",
          I18n.t(UiKeys.formDrillAgain),
          onClick.mapToUnit --> Observer[Unit](_ => phaseVar.set(DrillPhase.Setup)),
        ),
        a(
          cls := "btn",
          AppRouter.router.navigateTo(Page.TagDetail(tag.id)),
          I18n.t(UiKeys.formDrillBack),
        ),
      ),
    )
  }
}
