package gathedge.backend.service

import gathedge.backend.TestDataSource
import gathedge.backend.config.AppConfig
import gathedge.backend.db.{GroupRepository, TextSearch, WordFormRow, WordRepository, WordRow}
import gathedge.shared.domain.{Gender, PartOfSpeech, Tag, TranslationFilter, WordLanguage}
import gathedge.shared.dto.{
  BulkUploadManualPair,
  BulkUploadManualWord,
  BulkUploadSelectedTranslation,
  CreateWordRequest,
  NewTranslation,
  Paging,
  TagPairInput,
  TagPairWord,
  TaggedPair,
  WordSort,
}
import gathedge.shared.i18n.{MessageKeys, MessageRef}
import zio._
import zio.test._

import java.util.concurrent.TimeUnit

/** The vocabulary against SQLite, which is where everything but referential integrity is exercised.
  *
  * The service takes an `Option[Long]` reader throughout, and half of what is worth asserting here is what happens when
  * it is `None`: a visitor with no session sees the same words and none of the tag marks.
  */
object WordServiceSpec extends ZIOSpecDefault {

  private val layer = {
    (TestDataSource.sqlite >>> (WordRepository.test ++ GroupRepository.test)) ++
      AppConfig.live ++ RateLimiter.live >+> WordService.live
  }

  /** `AppConfig.live` with `quotas` overridden — the tests below need thresholds small enough to reach in a handful of
    * calls, and this is `TestAuthLayers.configWith`'s pattern for the same reason.
    */
  private def layerWithQuotas(tagsSoft: Int, tagsHard: Int, pairsSoft: Int, pairsHard: Int) = {
    val config = AppConfig.live.project(cfg => {
      cfg.copy(quotas = {
        cfg.quotas.copy(
          tagsPerUserSoft = tagsSoft,
          tagsPerUserHard = tagsHard,
          wordPairsPerUserSoft = pairsSoft,
          wordPairsPerUserHard = pairsHard,
        )
      })
    })
    (TestDataSource.sqlite >>> (WordRepository.test ++ GroupRepository.test)) ++
      config ++ RateLimiter.live >+> WordService.live
  }

  /** Unwraps [[WordService.createTag]]'s [[gathedge.shared.dto.TagResponse]] down to the [[Tag]] most tests only need —
    * the quota tests below are what actually reads the wrapper. Shadowing the name rather than calling it something
    * else keeps the rest of this file's calls unchanged.
    */
  private def createTag(name: String, userId: Long): ZIO[WordService, WordFailure, Tag] = {
    WordService.createTag(name, userId).map(_.tag)
  }

  /** Puts `tagId` (owned by `ownerId`) into a fresh group and adds `memberId` to that group's roster as a plain member
    * — the fixture the group-editing-rights tests below build on. Goes straight through `GroupRepository`/
    * `WordRepository.setTagGroup` rather than `GroupService` (not part of this spec's layer): what is under test here
    * is `WordService.requireEditableTag`'s own read of group membership, not `GroupService` itself, which
    * `GroupServiceSpec` covers.
    */
  private def putInGroupWith(
    tagId: Long,
    ownerId: Long,
    memberId: Long,
  ): ZIO[GroupRepository & WordRepository, Nothing, Unit] = {
    for {
      now   <- Clock.currentTime(TimeUnit.MILLISECONDS)
      group <- GroupRepository.insertGroup("group", "group", s"code-$tagId-$ownerId-$memberId", ownerId, now).orDie
      _     <- GroupRepository.insertMembership(group.id, ownerId, "admin", now).orDie
      _     <- GroupRepository.insertMembership(group.id, memberId, "member", now).orDie
      _     <- WordRepository.setTagGroup(tagId, Some(group.id)).orDie
    } yield ()
  }

  private def copyTag(tagId: Long, userId: Long): ZIO[WordService, WordFailure, Tag] = {
    WordService.copyTag(tagId, userId).map(_.tag)
  }

  private def createTagWithPairs(
    name: String,
    pairs: List[TagPairInput],
    userId: Long,
  ): ZIO[WordService, WordFailure, Tag] =
    WordService.createTagWithPairs(name, pairs, userId).map(_.tag)

  /** A dictionary row, as the importer would write it: no author, and a rank that decides where it lands in a search.
    */
  private def dictionaryWord(
    language: WordLanguage,
    text: String,
    pos: PartOfSpeech = PartOfSpeech.Noun,
    gender: Option[Gender] = None,
    rank: Int = 1,
  ): WordRow = {
    WordRow(
      id = 0L,
      language = WordLanguage.code(language),
      text = text,
      textNorm = text.toLowerCase,
      partOfSpeech = PartOfSpeech.code(pos),
      gender = Gender.toColumn(gender),
      frequencyRank = rank,
      source = WordService.dictionarySource,
      createdBy = None,
      createdAt = 0L,
      textSearch = TextSearch.fold(text.toLowerCase),
    )
  }

  private def seed: RIO[WordRepository, Unit] = {
    for {
      haus <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haus", gender = Some(Gender.Das), rank = 1))
      haz  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "ház", rank = 1))
      hau  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haufen", gender = Some(Gender.Der), rank = 900))
      _    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "hauen", pos = PartOfSpeech.Verb, rank = 500))
      _    <- WordRepository.insertTranslationPair(haus.id, haz.id, WordService.dictionaryOrigin, None, 0L)
      _     = hau
    } yield ()
  }

  /** Every Hungarian translation of `Haus` the cap test needs, ranked so their order is decided rather than incidental.
    * `ház` is already seeded at rank 1, so these are the second through fourth.
    */
  private def seedTranslations: RIO[WordRepository, List[Long]] = {
    for {
      haus <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haus", gender = Some(Gender.Das), rank = 1))
      ids  <- ZIO.foreach(List("otthon" -> 2, "lakás" -> 3, "hajlék" -> 4)) { case (text, rank) =>
                for {
                  word <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, text, rank = rank))
                  _    <- WordRepository.insertTranslationPair(
                            haus.id,
                            word.id,
                            WordService.dictionaryOrigin,
                            None,
                            0L,
                          )
                } yield word.id
              }
    } yield ids
  }

  /** The listing, German into Hungarian by default — the direction the screen opens in. The languages are parameters
    * because a practice pair is written in both directions, and the mirror is only visible from the other side.
    */
  private def list(
    search: Option[String] = None,
    reader: Option[Long] = None,
    tagId: Option[Long] = None,
    mine: Boolean = false,
    language: WordLanguage = WordLanguage.De,
    target: WordLanguage = WordLanguage.Hu,
    translationFilter: TranslationFilter = TranslationFilter.All,
    mainOnly: Boolean = false,
  ) = {
    WordService.list(
      page = Paging.firstPage,
      pageSize = 20,
      language = Some(language),
      search = search,
      partOfSpeech = None,
      tagId = tagId,
      mine = mine,
      target = target,
      translationFilter = translationFilter,
      mainOnly = mainOnly,
      sort = None,
      descending = false,
      reader = reader,
    )
  }

  /** [[list]] with an explicit ordering — the sort parameters are the only thing the tests using it vary. */
  private def listSorted(
    sort: String,
    descending: Boolean,
    tagId: Option[Long] = None,
    reader: Option[Long] = None,
  ) = {
    WordService.list(
      page = Paging.firstPage,
      pageSize = 20,
      language = Some(WordLanguage.De),
      search = None,
      partOfSpeech = None,
      tagId = tagId,
      mine = false,
      target = WordLanguage.Hu,
      translationFilter = TranslationFilter.All,
      mainOnly = false,
      sort = Some(sort),
      descending = descending,
      reader = reader,
    )
  }

  private def coreSpec = {
    suite("core")(
      test("a search is a prefix of the word, commonest first, and carries its translations") {
        for {
          _    <- seed
          page <- list(search = Some("hau"))
        } yield assertTrue(
          page.total == 3L,
          // Rank decides the order, which is what makes a two-letter search useful.
          page.items.map(_.word.text) == List("Haus", "hauen", "Haufen"),
          page.items.head.translations.map(_.text) == List("ház"),
          // No reader, so no tags — and no failure either.
          page.items.forall(_.tagIds.isEmpty),
        )
      },
      test("a search finds an accented word from an unaccented spelling, and vice versa") {
        for {
          _      <- seed
          _      <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Häuser", rank = 2))
          hauser <- list(search = Some("hau"))
          haz    <- list(search = Some("haz"), language = WordLanguage.Hu, target = WordLanguage.De)
        } yield assertTrue(
          hauser.items.map(_.word.text).contains("Häuser"),
          haz.items.map(_.word.text) == List("ház"),
        )
      },
      test("the translation filter narrows to words with a translation, in the target language or in any") {
        for {
          haus   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haus", gender = Some(Gender.Das)))
          haz    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "ház"))
          hauen  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "hauen", pos = PartOfSpeech.Verb))
          hit    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.En, "hit"))
          _      <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haufen", gender = Some(Gender.Der)))
          _      <- WordRepository.insertTranslationPair(haus.id, haz.id, WordService.dictionaryOrigin, None, 0L)
          // Into English, not the Hungarian target: satisfies "any language" but not "the target language".
          _      <- WordRepository.insertTranslationPair(hauen.id, hit.id, WordService.dictionaryOrigin, None, 0L)
          all    <- list(search = Some("hau"))
          target <- list(search = Some("hau"), translationFilter = TranslationFilter.HasTarget)
          any    <- list(search = Some("hau"), translationFilter = TranslationFilter.HasAny)
        } yield assertTrue(
          all.total == 3L,
          target.items.map(_.word.text) == List("Haus"),
          any.items.map(_.word.text).toSet == Set("Haus", "hauen"),
        )
      },
      test("a German noun keeps its article, and two words differing only by it are two words") {
        for {
          lake <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "See", gender = Some(Gender.Der)))
          sea  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "See", gender = Some(Gender.Die)))
          page <- list(search = Some("see"))
        } yield assertTrue(
          lake.id != sea.id,
          page.total == 2L,
          page.items.flatMap(_.word.gender).toSet == Set(Gender.Der, Gender.Die),
        )
      },
      test("adding a word that already exists answers the existing one, with everybody's translations on it") {
        for {
          _      <- seed
          before <- list(search = Some("haus"))
          detail <- WordService.create(
                      CreateWordRequest(
                        WordLanguage.De,
                        "Haus",
                        PartOfSpeech.Noun,
                        Some(Gender.Das),
                        List(NewTranslation(WordLanguage.Hu, "otthon", None, None)),
                        Nil,
                      ),
                      userId = 7L,
                    )
          after  <- list(search = Some("haus"))
        } yield assertTrue(
          // One row before and one after: the word was found, not created a second time.
          before.total == 1L,
          after.total == 1L,
          detail.word.id == before.items.head.word.id,
          // The dictionary's translation and the caller's, in that order.
          detail.translations.map(_.word.text) == List("ház", "otthon"),
          detail.translations.map(_.origin) == List(WordService.dictionaryOrigin, WordService.userOrigin),
        )
      },
      test("the same translation twice from the same reader is a conflict; from another reader it is not") {
        for {
          _       <- seed
          word    <- list(search = Some("haus")).map(_.items.head.word)
          _       <- WordService.addTranslation(word.id, NewTranslation(WordLanguage.Hu, "otthon", None, None), 1L)
          again   <- WordService.addTranslation(word.id, NewTranslation(WordLanguage.Hu, "otthon", None, None), 1L).either
          another <- WordService.addTranslation(word.id, NewTranslation(WordLanguage.Hu, "otthon", None, None), 2L)
        } yield assertTrue(
          again == Left(WordFailure.DuplicateTranslation),
          another.translations.count(_.word.text == "otthon") == 2,
        )
      },
      test("tagging is idempotent, and untagging something untagged is nothing to do") {
        for {
          _     <- seed
          tag   <- createTag("lesson1", 1L)
          word  <- list(search = Some("haus")).map(_.items.head.word)
          _     <- WordService.tagWord(word.id, tag.id, 1L)
          _     <- WordService.tagWord(word.id, tag.id, 1L)
          after <- list(reader = Some(1L))
          _     <- WordService.untagWord(word.id, tag.id, 1L)
          _     <- WordService.untagWord(word.id, tag.id, 1L)
          gone  <- list(reader = Some(1L))
          tags  <- WordService.listTags(1L)
        } yield assertTrue(
          after.items.count(_.tagIds.contains(tag.id)) == 1,
          gone.items.forall(_.tagIds.isEmpty),
          tags.map(_.name) == List("lesson1"),
        )
      },
      test("somebody else's tag answers the same as one that does not exist") {
        for {
          _      <- seed
          tag    <- createTag("theirs", 1L)
          word   <- list(search = Some("haus")).map(_.items.head.word)
          denied <- WordService.tagWord(word.id, tag.id, 2L).either
          absent <- WordService.tagWord(word.id, 9999L, 2L).either
        } yield assertTrue(denied == Left(WordFailure.TagNotFound), absent == Left(WordFailure.TagNotFound))
      },
      test("a group member may now edit the group's tag content, but a non-member stranger still cannot") {
        for {
          _        <- seed
          tag      <- createTag("shared", 1L)
          _        <- putInGroupWith(tag.id, ownerId = 1L, memberId = 2L)
          word     <- list(search = Some("haus")).map(_.items.head.word)
          member   <- WordService.tagWord(word.id, tag.id, 2L).either
          // `tagsFor`/`pairsFor` are widened to a group tag's members, not just its owner, so both the owner's and
          // the editing member's own listing view now reflect the write.
          byOwner  <- list(search = Some("haus"), reader = Some(1L))
          byMember <- list(search = Some("haus"), reader = Some(2L))
          stranger <- WordService.tagWord(word.id, tag.id, 3L).either
        } yield assertTrue(
          member.isRight,
          byOwner.items.head.tagIds == List(tag.id),
          byMember.items.head.tagIds == List(tag.id),
          stranger == Left(WordFailure.TagNotFound),
        )
      },
      test("a group member may mark a practice pair on the group's tag, charged against the tag owner's own quota") {
        for {
          _        <- seed
          tag      <- createTag("shared2", 1L)
          _        <- putInGroupWith(tag.id, ownerId = 1L, memberId = 2L)
          page     <- list(search = Some("haus"))
          word      = page.items.head.word
          haz       = page.items.head.translations.head.wordId
          marked   <- WordService.selectPair(word.id, tag.id, haz, 2L).either
          byOwner  <- list(search = Some("haus"), reader = Some(1L))
          byMember <- list(search = Some("haus"), reader = Some(2L))
        } yield assertTrue(
          marked.isRight,
          byOwner.items.head.pairs == List(TaggedPair(tag.id, haz)),
          byMember.items.head.pairs == List(TaggedPair(tag.id, haz)),
        )
      },
      test("listTags marks a group's tag editableByMe for a member who does not own it, but not for a stranger") {
        for {
          _        <- seed
          tag      <- createTag("shared4", 1L)
          _        <- putInGroupWith(tag.id, ownerId = 1L, memberId = 2L)
          byOwner  <- WordService.listTags(1L)
          byMember <- WordService.listTags(2L)
          byOther  <- WordService.listTags(3L)
        } yield assertTrue(
          byOwner.find(_.id == tag.id).exists(t => t.ownedByMe && t.editableByMe),
          byMember.find(_.id == tag.id).exists(t => !t.ownedByMe && t.editableByMe),
          // `listTags` still answers every tag globally, marked `ownedByMe` — a stranger sees the row, just not as
          // editable, same as they see anyone else's ordinary tag.
          byOther.find(_.id == tag.id).exists(t => !t.ownedByMe && !t.editableByMe),
        )
      },
      test("renaming or deleting a group's tag stays refused for a non-owner member — only content editing widened") {
        for {
          _       <- seed
          tag     <- createTag("shared3", 1L)
          _       <- putInGroupWith(tag.id, ownerId = 1L, memberId = 2L)
          renamed <- WordService.renameTag(tag.id, "nope", 2L).either
          deleted <- WordService.deleteTag(tag.id, 2L).either
        } yield assertTrue(renamed == Left(WordFailure.TagNotFound), deleted == Left(WordFailure.TagNotFound))
      },
      test("a tag name is unique per account, case-insensitively, and may not be one the practice screen reserves") {
        for {
          _         <- createTag("Lesson1", 1L)
          duplicate <- createTag("lesson1", 1L).either
          otherUser <- createTag("lesson1", 2L).either
          reserved  <- createTag("all_unknown", 1L).either
          blank     <- createTag("   ", 1L).either
        } yield assertTrue(
          duplicate == Left(WordFailure.DuplicateTag),
          otherUser.isRight,
          reserved.isLeft,
          blank.isLeft,
        )
      },
      test(
        "renaming a tag keeps its own current name usable, refuses a different tag's, and answers TagNotFound for somebody else's"
      ) {
        for {
          lesson1 <- createTag("lesson1", 1L)
          lesson2 <- createTag("lesson2", 1L)
          renamed <- WordService.renameTag(lesson1.id, "renamed", 1L).map(_.tag)
          same    <- WordService.renameTag(lesson1.id, "Renamed", 1L).either
          clash   <- WordService.renameTag(lesson1.id, "lesson2", 1L).either
          denied  <- WordService.renameTag(lesson2.id, "nope", 2L).either
          tags    <- WordService.listTags(1L)
        } yield assertTrue(
          renamed.name == "renamed",
          same.isRight,
          clash == Left(WordFailure.DuplicateTag),
          denied == Left(WordFailure.TagNotFound),
          tags.map(_.name).toSet == Set("Renamed", "lesson2"),
        )
      },
      test("listing tags answers every account's, own first, each marked whether the caller owns it") {
        for {
          _      <- createTag("zebra", 2L)
          mine   <- createTag("apple", 1L)
          seen   <- WordService.listTags(1L)
          theirs <- WordService.listTags(2L)
        } yield assertTrue(
          // Both tags are visible to both accounts — the whole point of global visibility — but each sees their own
          // marked and sorted ahead of the other's, regardless of alphabetical order.
          seen.map(_.name) == List("apple", "zebra"),
          seen.map(_.ownedByMe) == List(true, false),
          theirs.map(_.name) == List("zebra", "apple"),
          theirs.map(_.ownedByMe) == List(true, false),
          mine.ownedByMe,
        )
      },
      test("copying a tag copies its word memberships and marked pairs as an independent snapshot") {
        for {
          _                       <- seed
          shared                  <- createTag("shared", 1L)
          page                    <- list(search = Some("haus"), reader = Some(1L))
          word                     = page.items.head.word
          haz                      = page.items.head.translations.head.wordId
          _                       <- WordService.selectPair(word.id, shared.id, haz, 1L)
          copy                    <- copyTag(shared.id, 2L)
          seenBy2                 <- WordService.listTags(2L)
          snapshot                <- list(search = Some("haus"), reader = Some(2L), tagId = Some(copy.id))
          write                   <- WordService.tagWord(word.id, copy.id, 2L).either
          // Independence, both directions: a later change to either tag must not reach the other.
          _                       <- WordService.deselectPair(word.id, shared.id, haz, 1L)
          copyAfterSourceMutation <- list(search = Some("haus"), reader = Some(2L), tagId = Some(copy.id))
          _                       <- WordService.untagWord(word.id, copy.id, 2L)
          sourceAfterCopyMutation <- list(search = Some("haus"), reader = Some(1L), tagId = Some(shared.id))
        } yield assertTrue(
          copy.id != shared.id,
          copy.name == "shared",
          copy.ownedByMe,
          // The snapshot carried both words the mark filed under the source tag over, not an empty tag — marking a
          // pair puts the translation under the tag as well as the word, which `wordCount` counts.
          copy.wordCount == 2L,
          snapshot.items.head.pairs == List(TaggedPair(copy.id, haz)),
          seenBy2.find(_.id == copy.id).exists(_.ownedByMe),
          // The copy is the copier's own to write through, unlike the original.
          write.isRight,
          // Unmarking the source's pair afterward leaves the copy's mark exactly as it was at copy time.
          copyAfterSourceMutation.items.head.pairs == List(TaggedPair(copy.id, haz)),
          // And untagging the word from the copy afterward leaves the source's own membership untouched.
          sourceAfterCopyMutation.items.map(_.word.id) == List(word.id),
        )
      },
      test("copying answers TagNotFound for a missing source, and DuplicateTag for a name the copier already has") {
        for {
          original  <- createTag("theirs", 1L)
          _         <- createTag("theirs", 2L) // the copier already has this name
          absent    <- copyTag(9999L, 2L).either
          collision <- copyTag(original.id, 2L).either
        } yield assertTrue(
          absent == Left(WordFailure.TagNotFound),
          collision == Left(WordFailure.DuplicateTag),
        )
      },
      test("'only mine' with no session is an empty answer rather than the whole dictionary") {
        for {
          _         <- seed
          anonymous <- list(mine = true)
          reader    <- list(mine = true, reader = Some(1L))
        } yield assertTrue(anonymous.total == 0L, anonymous.items.isEmpty, reader.total == 0L)
      },
      test("only what the reader tagged is theirs, whoever else tagged it") {
        for {
          _     <- seed
          mine  <- createTag("mine", 1L)
          yours <- createTag("yours", 2L)
          words <- list().map(_.items.map(_.word.id))
          _     <- WordService.tagWord(words.head, mine.id, 1L)
          _     <- WordService.tagWord(words(1), yours.id, 2L)
          first <- list(mine = true, reader = Some(1L))
          other <- list(mine = true, reader = Some(2L))
        } yield assertTrue(
          first.items.map(_.word.id) == List(words.head),
          other.items.map(_.word.id) == List(words(1)),
        )
      },
      test("a word that is not there is a NotFound rather than an empty detail") {
        for {
          missing <- WordService.detail(4242L, None).either
        } yield assertTrue(missing == Left(WordFailure.NotFound))
      },
      test("a reader may remove their own translation and nobody else's") {
        for {
          _       <- seed
          word    <- list(search = Some("haus")).map(_.items.head.word)
          detail  <- WordService.addTranslation(word.id, NewTranslation(WordLanguage.Hu, "lak", None, None), 1L)
          own      = detail.translations.find(_.word.text == "lak").map(_.id).getOrElse(0L)
          imported = detail.translations.find(_.word.text == "ház").map(_.id).getOrElse(0L)
          theirs  <- WordService.removeTranslation(word.id, own, 2L).either
          dict    <- WordService.removeTranslation(word.id, imported, 1L).either
          _       <- WordService.removeTranslation(word.id, own, 1L)
          after   <- WordService.detail(word.id, Some(1L))
        } yield assertTrue(
          theirs == Left(WordFailure.NotFound),
          dict == Left(WordFailure.NotFound),
          after.translations.map(_.word.text) == List("ház"),
        )
      },
      test("marking a translation files both words under the tag and records the pair both ways") {
        for {
          _      <- seed
          tag    <- createTag("lesson1", 1L)
          page   <- list(search = Some("haus"), reader = Some(1L))
          word    = page.items.head.word
          haz     = page.items.head.translations.head.wordId
          _      <- WordService.selectPair(word.id, tag.id, haz, 1L)
          after  <- list(search = Some("haus"), reader = Some(1L))
          mirror <- list(search = Some("ház"), reader = Some(1L), language = WordLanguage.Hu, target = WordLanguage.De)
          detail <- WordService.detail(word.id, Some(1L))
          seen   <- WordService.detail(word.id, None)
        } yield assertTrue(
          after.items.head.pairs == List(TaggedPair(tag.id, haz)),
          // The word page marks the same chips as the listing, which is what it needs the marks for.
          detail.pairs == List(TaggedPair(tag.id, haz)),
          detail.tags.map(_.name) == List("lesson1"),
          // A visitor has no marks, the way they have no tags — the page is public.
          seen.pairs.isEmpty,
          // The word is filed even though nobody ticked it: a chip is a first click too.
          after.items.head.tagIds == List(tag.id),
          // And so is the translation, in both the membership and the pair — a pair whose answer is not collected is a
          // question with a missing half.
          mirror.items.head.tagIds == List(tag.id),
          mirror.items.head.pairs == List(TaggedPair(tag.id, word.id)),
        )
      },
      test("marking twice is nothing to do, and unmarking leaves both words tagged") {
        for {
          _      <- seed
          tag    <- createTag("lesson1", 1L)
          page   <- list(search = Some("haus"), reader = Some(1L))
          word    = page.items.head.word
          haz     = page.items.head.translations.head.wordId
          _      <- WordService.selectPair(word.id, tag.id, haz, 1L)
          _      <- WordService.selectPair(word.id, tag.id, haz, 1L)
          twice  <- list(search = Some("haus"), reader = Some(1L))
          _      <- WordService.deselectPair(word.id, tag.id, haz, 1L)
          _      <- WordService.deselectPair(word.id, tag.id, haz, 1L)
          after  <- list(search = Some("haus"), reader = Some(1L))
          mirror <- list(search = Some("ház"), reader = Some(1L), language = WordLanguage.Hu, target = WordLanguage.De)
        } yield assertTrue(
          twice.items.head.pairs == List(TaggedPair(tag.id, haz)),
          // Both directions of the pair go, and neither word leaves the tag: that is the tick's job.
          after.items.head.pairs.isEmpty,
          mirror.items.head.pairs.isEmpty,
          after.items.head.tagIds == List(tag.id),
          mirror.items.head.tagIds == List(tag.id),
        )
      },
      test("a translation the word does not have is a NotFound, and so is somebody else's tag") {
        for {
          _        <- seed
          tag      <- createTag("lesson1", 1L)
          page     <- list(search = Some("hau"), reader = Some(1L))
          word      = page.items.head.word
          haz       = page.items.head.translations.head.wordId
          unrelated = page.items.map(_.word.id).filterNot(_ == word.id).head
          notEdge  <- WordService.selectPair(word.id, tag.id, unrelated, 1L).either
          noWord   <- WordService.selectPair(4242L, tag.id, haz, 1L).either
          theirs   <- WordService.selectPair(word.id, tag.id, haz, 2L).either
          noTag    <- WordService.deselectPair(word.id, 9999L, haz, 1L).either
        } yield assertTrue(
          notEdge == Left(WordFailure.NotFound),
          noWord == Left(WordFailure.NotFound),
          theirs == Left(WordFailure.TagNotFound),
          noTag == Left(WordFailure.TagNotFound),
        )
      },
      test("a marked translation is carried even when it falls outside the three a row shows") {
        for {
          _        <- seed
          extra    <- seedTranslations
          tag      <- createTag("lesson1", 1L)
          page     <- list(search = Some("haus"), reader = Some(1L))
          word      = page.items.head.word
          last      = extra.last
          _        <- WordService.selectPair(word.id, tag.id, last, 1L)
          reader   <- list(search = Some("haus"), reader = Some(1L))
          stranger <- list(search = Some("haus"))
        } yield assertTrue(
          // The cap is what keeps the cell a line; the union with what the reader marked is what makes a chip
          // reversible, since one they cannot see they can never unclick.
          stranger.items.head.translations.size == 3,
          reader.items.head.translations.size == 4,
          reader.items.head.translations.map(_.wordId).contains(last),
          reader.items.head.pairs == List(TaggedPair(tag.id, last)),
        )
      },
      test("untagging a word clears its practice pairs in that tag, both ways round") {
        for {
          _      <- seed
          tag    <- createTag("lesson1", 1L)
          page   <- list(search = Some("haus"), reader = Some(1L))
          word    = page.items.head.word
          haz     = page.items.head.translations.head.wordId
          _      <- WordService.selectPair(word.id, tag.id, haz, 1L)
          _      <- WordService.untagWord(word.id, tag.id, 1L)
          after  <- list(search = Some("haus"), reader = Some(1L))
          mirror <- list(search = Some("ház"), reader = Some(1L), language = WordLanguage.Hu, target = WordLanguage.De)
        } yield assertTrue(
          after.items.head.tagIds.isEmpty,
          after.items.head.pairs.isEmpty,
          // The translation keeps the tag — only the word that left takes its pairs with it.
          mirror.items.head.tagIds == List(tag.id),
          mirror.items.head.pairs.isEmpty,
        )
      },
      test("a reader sees only their own marks") {
        for {
          _     <- seed
          mine  <- createTag("mine", 1L)
          yours <- createTag("yours", 2L)
          page  <- list(search = Some("haus"))
          word   = page.items.head.word
          haz    = page.items.head.translations.head.wordId
          _     <- WordService.selectPair(word.id, mine.id, haz, 1L)
          _     <- WordService.selectPair(word.id, yours.id, haz, 2L)
          first <- list(search = Some("haus"), reader = Some(1L))
          other <- list(search = Some("haus"), reader = Some(2L))
        } yield assertTrue(
          first.items.head.pairs == List(TaggedPair(mine.id, haz)),
          other.items.head.pairs == List(TaggedPair(yours.id, haz)),
        )
      },
      test("a word added with a translation is marked for practice under the tag it is filed in") {
        for {
          _      <- seed
          tag    <- createTag("lesson1", 1L)
          detail <- WordService.create(
                      CreateWordRequest(
                        WordLanguage.De,
                        "Brot",
                        PartOfSpeech.Noun,
                        Some(Gender.Das),
                        List(NewTranslation(WordLanguage.Hu, "kenyér", None, None)),
                        List(tag.id),
                      ),
                      userId = 1L,
                    )
          page   <- list(search = Some("brot"), reader = Some(1L))
          kenyer  = detail.translations.find(_.word.text == "kenyér").map(_.word.id).getOrElse(0L)
        } yield assertTrue(
          // A translation somebody bothered to type is the clearest statement of "this is the answer I want", so the
          // form produces the same state a chip click does.
          page.items.head.pairs == List(TaggedPair(tag.id, kenyer)),
          page.items.head.tagIds == List(tag.id),
        )
      },
      test(
        "a main word and variant type link the new word into word_forms, idempotently, and a foreign language is refused"
      ) {
        for {
          _        <- seed
          haus     <- list(search = Some("haus")).map(_.items.head.word)
          detail   <- WordService.create(
                        CreateWordRequest(
                          WordLanguage.De,
                          "Häuser",
                          PartOfSpeech.Noun,
                          Some(Gender.Das),
                          Nil,
                          Nil,
                          mainWordId = Some(haus.id),
                          variantType = Some("plural"),
                        ),
                        userId = 7L,
                      )
          again    <- WordService.create(
                        CreateWordRequest(
                          WordLanguage.De,
                          "Häuser",
                          PartOfSpeech.Noun,
                          Some(Gender.Das),
                          Nil,
                          Nil,
                          mainWordId = Some(haus.id),
                          variantType = Some("plural"),
                        ),
                        userId = 7L,
                      )
          mismatch <- WordService
                        .create(
                          CreateWordRequest(
                            // A Hungarian word naming the German `Haus` as its main word: an inflection always shares
                            // its lemma's language, so this is refused rather than linked.
                            WordLanguage.Hu,
                            "hazak",
                            PartOfSpeech.Noun,
                            None,
                            Nil,
                            Nil,
                            mainWordId = Some(haus.id),
                            variantType = Some("plural"),
                          ),
                          userId = 7L,
                        )
                        .either
          forms    <- WordRepository.formsOf(haus.id)
        } yield assertTrue(
          detail.mainWords.map(m => (m.word.id, m.relation)) == List((haus.id, "plural")),
          again.word.id == detail.word.id,
          forms.size == 1,
          mismatch == Left(
            WordFailure.ValidationError(Map("mainWordId" -> MessageRef(MessageKeys.wordMainWordLanguageMismatch)))
          ),
        )
      },
      test("an unknown sort column falls back to the listing's own order rather than failing") {
        for {
          _       <- seed
          unknown <- WordService.list(
                       page = Paging.firstPage,
                       pageSize = 20,
                       language = Some(WordLanguage.De),
                       search = None,
                       partOfSpeech = None,
                       tagId = None,
                       mine = false,
                       target = WordLanguage.Hu,
                       translationFilter = TranslationFilter.All,
                       mainOnly = false,
                       sort = Some("nonsense"),
                       descending = false,
                       reader = None,
                     )
          byText  <- WordService.list(
                       page = Paging.firstPage,
                       pageSize = 20,
                       language = Some(WordLanguage.De),
                       search = None,
                       partOfSpeech = None,
                       tagId = None,
                       mine = false,
                       target = WordLanguage.Hu,
                       translationFilter = TranslationFilter.All,
                       mainOnly = false,
                       sort = Some(WordSort.text),
                       descending = true,
                       reader = None,
                     )
        } yield assertTrue(
          unknown.items.map(_.word.text) == List("Haus", "hauen", "Haufen"),
          byText.items.map(_.word.text).headOption.contains("Haus"),
        )
      },
      test("newest in tag orders by the tick, not by the word, and only while a tag narrows the listing") {
        for {
          _      <- seed
          tag    <- createTag("lesson1", 1L)
          haus   <- list(search = Some("haus")).map(_.items.head.word)
          haufen <- list(search = Some("hauf")).map(_.items.head.word)
          _      <- WordService.tagWord(haus.id, tag.id, 1L)
          // Two ticks a minute apart: with both at the same instant the order under test would be incidental.
          _      <- TestClock.adjust(1.minute)
          _      <- WordService.tagWord(haufen.id, tag.id, 1L)
          newest <- listSorted(WordSort.added, descending = true, tagId = Some(tag.id), reader = Some(1L))
          oldest <- listSorted(WordSort.added, descending = false, tagId = Some(tag.id), reader = Some(1L))
          noTag  <- listSorted(WordSort.added, descending = true, tagId = None, reader = Some(1L))
        } yield assertTrue(
          // `Haufen` is rank 900 and `Haus` rank 1, so this is the opposite of the listing's own order.
          newest.items.map(_.word.text) == List("Haufen", "Haus"),
          oldest.items.map(_.word.text) == List("Haus", "Haufen"),
          // The join matches each word once: the page holds the rows the count counted.
          newest.total == 2L,
          // Nothing to order by without a tag, so the listing keeps commonest-first rather than failing.
          noTag.items.map(_.word.text) == List("Haus", "hauen", "Haufen"),
        )
      },
      test("mainOnly drops a word that is itself a form of another word") {
        for {
          _        <- seed
          haus     <- list(search = Some("haus")).map(_.items.head.word)
          _        <- WordService.create(
                        CreateWordRequest(
                          WordLanguage.De,
                          "Häuser",
                          PartOfSpeech.Noun,
                          Some(Gender.Das),
                          Nil,
                          Nil,
                          mainWordId = Some(haus.id),
                          variantType = Some("plural"),
                        ),
                        userId = 7L,
                      )
          every    <- list(search = Some("häuser"))
          mainOnly <- list(search = Some("häuser"), mainOnly = true)
        } yield assertTrue(
          every.items.map(_.word.text).contains("Häuser"),
          !mainOnly.items.map(_.word.text).contains("Häuser"),
          mainOnly.total == 0L,
        )
      },
    ).provide(layer)
  }

  /** The two standing quotas (`AppConfig.quotas`), each run against thresholds small enough to reach in a handful of
    * calls — the shipped defaults (20/50 tags, 500/2000 pairs) would make the same tests impractically long. Every test
    * here gets its own [[layerWithQuotas]], so one test's tags never count toward another's threshold.
    */
  private def quotaSpec = {
    suite("usage quotas")(
      test("creating tags: no warning below the soft threshold, one from it to the hard one, and a block past it") {
        for {
          t1 <- WordService.createTag("t1", 1L)
          t2 <- WordService.createTag("t2", 1L)
          t3 <- WordService.createTag("t3", 1L)
          t4 <- WordService.createTag("t4", 1L)
          t5 <- WordService.createTag("t5", 1L)
          t6 <- WordService.createTag("t6", 1L).either
        } yield assertTrue(
          // Soft is 3: the first two tags (totals 1, 2) carry no warning.
          t1.warning.isEmpty,
          t2.warning.isEmpty,
          // From the third tag on (total 3, 4, 5) the write still succeeds, now with a warning — the account may hold
          // up to the hard limit itself before anything is refused.
          t3.warning.exists(_.key == MessageKeys.wordTagQuotaWarning),
          t4.warning.isDefined,
          t5.warning.isDefined,
          // Hard is 5: the sixth tag would make the total 6, past it, and is refused rather than warned.
          t6 == Left(WordFailure.TagQuotaExceeded(5)),
        )
      }.provide(layerWithQuotas(tagsSoft = 3, tagsHard = 5, pairsSoft = 1000, pairsHard = 1000)),
      test("marking pairs: no warning below the soft threshold, one from it to the hard one, and a block past it") {
        for {
          _     <- seed
          extra <- seedTranslations
          tag   <- createTag("lesson1", 1L)
          page  <- list(search = Some("haus"), reader = Some(1L))
          word   = page.items.head.word
          haz    = page.items.head.translations.head.wordId
          // Each mark writes two rows (one per direction), so four distinct translations cover the boundary in as
          // few calls as the tag test above needed tags.
          r1    <- WordService.selectPair(word.id, tag.id, haz, 1L)
          r2    <- WordService.selectPair(word.id, tag.id, extra.head, 1L)
          r3    <- WordService.selectPair(word.id, tag.id, extra(1), 1L)
          r4    <- WordService.selectPair(word.id, tag.id, extra(2), 1L).either
        } yield assertTrue(
          // Soft is 4: the first mark (total 2) carries no warning.
          r1.warning.isEmpty,
          // The second and third marks (total 4, 6) still succeed, now with a warning.
          r2.warning.exists(_.key == MessageKeys.wordPairQuotaWarning),
          r3.warning.isDefined,
          // Hard is 6: the fourth mark would make the total 8, past it, and is refused rather than warned.
          r4 == Left(WordFailure.PairQuotaExceeded(6)),
        )
      }.provide(layerWithQuotas(tagsSoft = 1000, tagsHard = 1000, pairsSoft = 4, pairsHard = 6)),
      test("a copy that would cross the pair quota's hard limit fails entirely, and leaves nothing written") {
        for {
          _           <- seed
          extra       <- seedTranslations
          shared      <- createTag("shared", 1L)
          page        <- list(search = Some("haus"), reader = Some(1L))
          word         = page.items.head.word
          haz          = page.items.head.translations.head.wordId
          // One mark under the source tag writes two pair rows — within the hard limit for the account that made it,
          // which is what proves the copy is blocked by the *copier's own total*, not by the source having too many.
          _           <- WordService.selectPair(word.id, shared.id, haz, 1L)
          // The copier already carries two rows of their own, under a tag of their own — close enough to the hard
          // limit that the two more rows the copy would add push them past it.
          mine        <- createTag("mine", 2L)
          _           <- WordService.selectPair(word.id, mine.id, extra.head, 2L)
          tagsBefore  <- WordRepository.countTagsOwnedBy(2L)
          pairsBefore <- WordRepository.countPairsOwnedBy(2L)
          blocked     <- WordService.copyTag(shared.id, 2L).either
          tagsAfter   <- WordRepository.countTagsOwnedBy(2L)
          pairsAfter  <- WordRepository.countPairsOwnedBy(2L)
        } yield assertTrue(
          blocked == Left(WordFailure.PairQuotaExceeded(3)),
          tagsBefore == 1L,
          pairsBefore == 2L,
          // Still just the one tag and its two rows — the check ran before the write, not as a rollback.
          tagsAfter == 1L,
          pairsAfter == 2L,
        )
      }.provide(layerWithQuotas(tagsSoft = 100, tagsHard = 100, pairsSoft = 100, pairsHard = 3)),
      test("a copy that would cross the tag quota's hard limit fails entirely, and leaves nothing written") {
        for {
          _          <- seed
          shared     <- createTag("shared", 1L)
          _          <- createTag("existing", 2L) // the copier already owns one tag, and the hard limit is one
          tagsBefore <- WordRepository.countTagsOwnedBy(2L)
          blocked    <- WordService.copyTag(shared.id, 2L).either
          tagsAfter  <- WordRepository.countTagsOwnedBy(2L)
        } yield assertTrue(
          blocked == Left(WordFailure.TagQuotaExceeded(1)),
          tagsBefore == 1L,
          // Still just the one tag: the tag half of the check ran, and refused, before any pair was even counted.
          tagsAfter == 1L,
        )
      }.provide(layerWithQuotas(tagsSoft = 1, tagsHard = 1, pairsSoft = 100, pairsHard = 100)),
      test(
        "creating a word with tagIds: a request whose tags × translations would cross the pair quota's hard limit " +
          "fails entirely, tags still attach, and no pairs are written"
      ) {
        for {
          tag1       <- createTag("t1", 1L)
          tag2       <- createTag("t2", 1L)
          blocked    <- WordService
                          .create(
                            CreateWordRequest(
                              WordLanguage.De,
                              "Haus",
                              PartOfSpeech.Noun,
                              Some(Gender.Das),
                              List(
                                NewTranslation(WordLanguage.Hu, "otthon", None, None),
                                NewTranslation(WordLanguage.Hu, "lakás", None, None),
                              ),
                              List(tag1.id, tag2.id),
                            ),
                            userId = 1L,
                          )
                          .either
          pairsAfter <- WordRepository.countPairsOwnedBy(1L)
          page       <- list(search = Some("haus"), reader = Some(1L))
          tagged      = page.items.head.tagIds
        } yield assertTrue(
          // Two tags × two translations is four new marks, eight rows — past a hard limit of six.
          blocked == Left(WordFailure.PairQuotaExceeded(6)),
          // The quota is checked before any mark is written, so none of the eight landed.
          pairsAfter == 0L,
          // Tag membership is never quota-gated: both tags are still attached even though marking was refused.
          tagged.toSet == Set(tag1.id, tag2.id),
        )
      }.provide(layerWithQuotas(tagsSoft = 1000, tagsHard = 1000, pairsSoft = 1000, pairsHard = 6)),
      test("creating a word with tagIds: crossing only the pair quota's soft threshold still succeeds, marks written") {
        for {
          tag        <- createTag("t1", 1L)
          detail     <- WordService.create(
                          CreateWordRequest(
                            WordLanguage.De,
                            "Haus",
                            PartOfSpeech.Noun,
                            Some(Gender.Das),
                            List(NewTranslation(WordLanguage.Hu, "otthon", None, None)),
                            List(tag.id),
                          ),
                          userId = 1L,
                        )
          pairsAfter <- WordRepository.countPairsOwnedBy(1L)
        } yield assertTrue(
          detail.translations.exists(_.word.text == "otthon"),
          // One tag × one translation is one new mark, two rows — at the soft threshold, still allowed through.
          pairsAfter == 2L,
        )
      }.provide(layerWithQuotas(tagsSoft = 1000, tagsHard = 1000, pairsSoft = 2, pairsHard = 100)),
      test(
        "creating a word with tagIds: a combination already marked is not charged again against the pair quota"
      ) {
        for {
          _      <- seed
          tag1   <- createTag("t1", 1L)
          tag2   <- createTag("t2", 1L)
          page   <- list(search = Some("haus"), reader = Some(1L))
          word    = page.items.head.word
          haz     = page.items.head.translations.head.wordId
          // "ház" is already marked under tag1 before the create call below — that combination must cost nothing again.
          _      <- WordService.selectPair(word.id, tag1.id, haz, 1L)
          before <- WordRepository.countPairsOwnedBy(1L)
          _      <- WordService.create(
                      CreateWordRequest(
                        WordLanguage.De,
                        "Haus",
                        PartOfSpeech.Noun,
                        Some(Gender.Das),
                        List(NewTranslation(WordLanguage.Hu, "ház", None, None)),
                        List(tag1.id, tag2.id),
                      ),
                      userId = 1L,
                    )
          after  <- WordRepository.countPairsOwnedBy(1L)
        } yield assertTrue(
          before == 2L,
          // Only tag2 × "ház" is new: two more rows, not four — a hard limit of 4 would refuse the double-charged
          // total (6) but allows the correctly-deduplicated one.
          after == 4L,
        )
      }.provide(layerWithQuotas(tagsSoft = 1000, tagsHard = 1000, pairsSoft = 1000, pairsHard = 4)),
    )
  }

  private def bulkUploadSpec = {
    suite("bulk upload")(
      test("a matched word carries whether it has a translation in any language, not only the counterpart") {
        for {
          haus    <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haus", gender = Some(Gender.Das)))
          hit     <- WordRepository.ensureWord(dictionaryWord(WordLanguage.En, "hit"))
          // Into English, not the requested (Hungarian) counterpart.
          _       <- WordRepository.insertTranslationPair(haus.id, hit.id, WordService.dictionaryOrigin, None, 0L)
          tag     <- createTag("upload", 1L)
          preview <- WordService.bulkUploadPreview(tag.id, "Haus", WordLanguage.De, WordLanguage.Hu, 1L)
          matched  = preview.matched.find(_.word.text == "Haus")
        } yield assertTrue(
          matched.exists(_.translations.isEmpty),
          matched.exists(_.hasAnyTranslation),
        )
      },
      test(
        "preview matches the source language first, offers only known cross-language translations, and writes nothing"
      ) {
        for {
          _           <- seed
          tag         <- createTag("upload", 1L)
          wordsBefore <- ZIO.serviceWithZIO[WordRepository](_.countWords)
          tagsBefore  <- WordRepository.countTagsOwnedBy(1L)
          preview     <- WordService.bulkUploadPreview(tag.id, "Haus ház brandneu", WordLanguage.De, WordLanguage.Hu, 1L)
          wordsAfter  <- ZIO.serviceWithZIO[WordRepository](_.countWords)
          tagsAfter   <- WordRepository.countTagsOwnedBy(1L)
          haus         = preview.matched.find(_.word.text == "Haus")
          haz          = preview.matched.find(_.word.text == "ház")
        } yield assertTrue(
          // "Haus" and "ház" are already in the dictionary in their own language; "brandneu" is in neither.
          preview.matched.map(_.word.text).toSet == Set("Haus", "ház"),
          preview.unmatched == List("brandneu"),
          // Each matched word's only translation shown is the one the dictionary already links it to, rendered with
          // its article since "Haus" is a German neuter noun.
          haus.exists(_.translations.map(_.text) == List("ház")),
          haz.exists(_.translations.map(_.text) == List("das Haus")),
          // A preview is a read: nothing new was written beyond the tag the test itself created.
          wordsAfter == wordsBefore,
          tagsAfter == tagsBefore,
        )
      },
      test(
        "matched dictionary rows sharing one bare text collapse to at most one noun and one non-noun, the way a reader thinks of it as one word"
      ) {
        for {
          _        <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Bitte", rank = 1))
          gendered <-
            WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Bitte", gender = Some(Gender.Die), rank = 2))
          _        <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "bitte", pos = PartOfSpeech.Other, rank = 3))
          adverb   <-
            WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "bitte", pos = PartOfSpeech.Adverb, rank = 4))
          hu       <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "kérem", rank = 1))
          _        <- WordRepository.insertTranslationPair(adverb.id, hu.id, WordService.dictionaryOrigin, None, 0L)
          tag      <- createTag("upload", 1L)
          preview  <- WordService.bulkUploadPreview(tag.id, "bitte", WordLanguage.De, WordLanguage.Hu, 1L)
        } yield assertTrue(
          // Of the two noun rows, the gendered one wins over the genderless duplicate; of the two non-noun rows, the
          // translated adverb wins over the untranslated one with no other signal to prefer it.
          preview.matched.map(_.word.id).toSet == Set(gendered.id, adverb.id)
        )
      },
      test("a leading der/die/das merges into the noun that follows it as one token, only when German is asked for") {
        for {
          tag         <- createTag("upload", 1L)
          germanSide  <- WordService.bulkUploadPreview(tag.id, "der Tisch neuwort", WordLanguage.De, WordLanguage.Hu, 1L)
          neitherSide <-
            WordService.bulkUploadPreview(tag.id, "der Tisch neuwort", WordLanguage.En, WordLanguage.Hu, 1L)
        } yield assertTrue(
          // German is one of the two declared languages: "der" merges into "der tisch" rather than showing up as its
          // own spurious token; the merge also marks it a noun, so it displays articled and capitalized. "neuwort"
          // (no article) is unaffected.
          germanSide.unmatched.toSet == Set("der Tisch", "neuwort"),
          // Neither declared language is German: no merging happens, so "der" and "tisch" are two separate tokens.
          neitherSide.unmatched.toSet == Set("der", "tisch", "neuwort"),
        )
      },
      test(
        "a bare German word and its der/die/das-prefixed variant collapse into one unmatched entry, capitalized as a noun"
      ) {
        for {
          tag     <- createTag("upload", 1L)
          preview <- WordService.bulkUploadPreview(tag.id, "Hund der Hund", WordLanguage.De, WordLanguage.Hu, 1L)
        } yield assertTrue(
          // Neither "hund" nor "der hund" is in the dictionary; since one of the two typed variants carried an
          // article, the pair collapses to one entry rather than two, capitalized and articled as a noun.
          preview.unmatched == List("der Hund")
        )
      },
      test("preview answers TagNotFound for somebody else's tag and one that does not exist") {
        for {
          tag    <- createTag("theirs", 1L)
          denied <- WordService.bulkUploadPreview(tag.id, "hello", WordLanguage.En, WordLanguage.De, 2L).either
          absent <- WordService.bulkUploadPreview(9999L, "hello", WordLanguage.En, WordLanguage.De, 1L).either
        } yield assertTrue(
          denied == Left(BulkUploadFailure.TagNotFound),
          absent == Left(BulkUploadFailure.TagNotFound),
        )
      },
      test("an empty file, or one over the byte limit, is refused before anything is written") {
        val invalidFile =
          BulkUploadFailure.ValidationError(Map("content" -> MessageRef(MessageKeys.wordBulkUploadInvalidFile)))
        for {
          tag       <- createTag("upload", 1L)
          blank     <- WordService.bulkUploadPreview(tag.id, "   ", WordLanguage.En, WordLanguage.De, 1L).either
          huge      <-
            WordService
              .bulkUploadPreview(
                tag.id,
                "a" * (WordService.maxBulkUploadBytes + 1),
                WordLanguage.En,
                WordLanguage.De,
                1L,
              )
              .either
          tagsAfter <- WordRepository.countTagsOwnedBy(1L)
        } yield assertTrue(
          blank == Left(invalidFile),
          huge == Left(invalidFile),
          // Neither refusal wrote anything beyond the tag the test itself already owned.
          tagsAfter == 1L,
        )
      },
      test("confirm answers TagNotFound for somebody else's tag and one that does not exist") {
        for {
          tag    <- createTag("theirs", 1L)
          denied <-
            WordService.bulkUploadConfirm(tag.id, WordLanguage.En, WordLanguage.De, Nil, Nil, Nil, Nil, 2L).either
          absent <-
            WordService.bulkUploadConfirm(9999L, WordLanguage.En, WordLanguage.De, Nil, Nil, Nil, Nil, 1L).either
        } yield assertTrue(
          denied == Left(BulkUploadFailure.TagNotFound),
          absent == Left(BulkUploadFailure.TagNotFound),
        )
      },
      test(
        "confirm tags an accepted matched word and marks its selected translation, leaving a declined one untouched"
      ) {
        for {
          _        <- seed
          tag      <- createTag("upload", 1L)
          preview  <- WordService.bulkUploadPreview(tag.id, "Haus ház brandneu", WordLanguage.De, WordLanguage.Hu, 1L)
          haus      = preview.matched.find(_.word.text == "Haus").get
          selection = BulkUploadSelectedTranslation(haus.word.id, haus.translations.head.wordId)
          added    <-
            WordService.bulkUploadConfirm(
              tag.id,
              WordLanguage.De,
              WordLanguage.Hu,
              List(haus.word.id),
              List(selection),
              Nil,
              Nil,
              1L,
            )
          dePage   <- list(reader = Some(1L), tagId = Some(tag.id))
          huPage   <- list(reader = Some(1L), tagId = Some(tag.id), language = WordLanguage.Hu, target = WordLanguage.De)
        } yield assertTrue(
          // Accepting "Haus" with its one selected translation still tags two words: marking a pair's translation
          // files both words under the tag, even though only "Haus" was named. "brandneu" was never previewed as a
          // match, so accepting nothing for it leaves it out entirely.
          added == 2,
          dePage.items.map(_.word.text) == List("Haus"),
          huPage.items.map(_.word.text) == List("ház"),
        )
      },
      test("confirm marks only the selected translation, not every one the dictionary has for the word") {
        for {
          _        <- seed
          hunIds   <- seedTranslations
          tag      <- createTag("upload", 1L)
          preview  <- WordService.bulkUploadPreview(tag.id, "Haus", WordLanguage.De, WordLanguage.Hu, 1L)
          haus      = preview.matched.find(_.word.text == "Haus").get
          picked    = hunIds(1) // "lakás" — deliberately not the first (rank-commonest) translation
          selection = BulkUploadSelectedTranslation(haus.word.id, picked)
          _        <-
            WordService.bulkUploadConfirm(
              tag.id,
              WordLanguage.De,
              WordLanguage.Hu,
              List(haus.word.id),
              List(selection),
              Nil,
              Nil,
              1L,
            )
          huPage   <- list(reader = Some(1L), tagId = Some(tag.id), language = WordLanguage.Hu, target = WordLanguage.De)
        } yield assertTrue(
          // The preview offered four Hungarian translations; only "lakás", the one the reader picked, gets tagged.
          haus.translations.size == 4,
          huPage.items.map(_.word.text) == List("lakás"),
        )
      },
      test("a manual pair creates both new words, links them as a translation, and tags and marks both") {
        for {
          tag    <- createTag("upload", 1L)
          pair    = BulkUploadManualPair("zzzsource", "zzztarget")
          added  <-
            WordService.bulkUploadConfirm(tag.id, WordLanguage.De, WordLanguage.Hu, Nil, Nil, List(pair), Nil, 1L)
          dePage <- list(reader = Some(1L), tagId = Some(tag.id))
          huPage <- list(reader = Some(1L), tagId = Some(tag.id), language = WordLanguage.Hu, target = WordLanguage.De)
        } yield assertTrue(
          added == 2,
          dePage.items.map(_.word.text.toLowerCase) == List("zzzsource"),
          huPage.items.map(_.word.text.toLowerCase) == List("zzztarget"),
          dePage.items.head.pairs.nonEmpty,
          huPage.items.head.pairs.nonEmpty,
        )
      },
      test("confirming the same manual pair twice inserts its translation edge only once") {
        for {
          tag    <- createTag("upload", 1L)
          pair    = BulkUploadManualPair("zzzsource", "zzztarget")
          first  <-
            WordService.bulkUploadConfirm(tag.id, WordLanguage.De, WordLanguage.Hu, Nil, Nil, List(pair), Nil, 1L)
          after1 <- ZIO.serviceWithZIO[WordRepository](_.countTranslations)
          second <-
            WordService.bulkUploadConfirm(tag.id, WordLanguage.De, WordLanguage.Hu, Nil, Nil, List(pair), Nil, 1L)
          after2 <- ZIO.serviceWithZIO[WordRepository](_.countTranslations)
        } yield assertTrue(first == 2, second == 2, after1 == after2)
      },
      test("a manual pair's German side keeps the gender its article named, is created as a noun, and is capitalized") {
        for {
          tag    <- createTag("upload", 1L)
          pair    = BulkUploadManualPair("der zzztisch", "zzzasztal")
          _      <- WordService.bulkUploadConfirm(tag.id, WordLanguage.De, WordLanguage.Hu, Nil, Nil, List(pair), Nil, 1L)
          dePage <- list(reader = Some(1L), tagId = Some(tag.id))
        } yield assertTrue(
          // German nouns are always capitalized; the target side (no article, not a noun) is left as typed.
          dePage.items.map(_.word.text) == List("Zzztisch"),
          dePage.items.head.word.gender.contains(Gender.Der),
        )
      },
      test("a standalone German word with an article is created capitalized") {
        for {
          tag   <- createTag("upload", 1L)
          word   = BulkUploadManualWord("die zzzblume", WordLanguage.De)
          added <-
            WordService.bulkUploadConfirm(tag.id, WordLanguage.En, WordLanguage.De, Nil, Nil, Nil, List(word), 1L)
          page  <- list(reader = Some(1L), tagId = Some(tag.id), language = WordLanguage.De, target = WordLanguage.En)
        } yield assertTrue(
          added == 1,
          page.items.map(_.word.text) == List("Zzzblume"),
          page.items.head.word.gender.contains(Gender.Die),
        )
      },
      test("a standalone word is created and tagged with no translation link") {
        for {
          tag    <- createTag("upload", 1L)
          word    = BulkUploadManualWord("zzzlonewolf", WordLanguage.En)
          before <- ZIO.serviceWithZIO[WordRepository](_.countTranslations)
          added  <-
            WordService.bulkUploadConfirm(tag.id, WordLanguage.En, WordLanguage.De, Nil, Nil, Nil, List(word), 1L)
          after  <- ZIO.serviceWithZIO[WordRepository](_.countTranslations)
          page   <- list(reader = Some(1L), tagId = Some(tag.id), language = WordLanguage.En, target = WordLanguage.De)
        } yield assertTrue(
          added == 1,
          page.items.map(_.word.text.toLowerCase) == List("zzzlonewolf"),
          page.items.head.pairs.isEmpty,
          after == before,
        )
      },
      test("the rate-limit budget is shared between preview and confirm") {
        for {
          tag     <- createTag("upload", 1L)
          warmup  <- ZIO.foreach(1 to RateLimiter.maxAttempts)(n => {
                       if (n % 2 == 0) {
                         WordService
                           .bulkUploadConfirm(tag.id, WordLanguage.En, WordLanguage.De, Nil, Nil, Nil, Nil, 1L)
                           .either
                       } else
                         WordService.bulkUploadPreview(tag.id, "hello", WordLanguage.En, WordLanguage.De, 1L).either
                     })
          blocked <- WordService.bulkUploadPreview(tag.id, "hello", WordLanguage.En, WordLanguage.De, 1L).either
        } yield assertTrue(warmup.forall(_.isRight), blocked == Left(BulkUploadFailure.RateLimited))
      },
    ).provide(layer)
  }

  private def suggestionsSpec = {
    suite("bulk upload suggestions")(
      test("a near-miss token gets a suggestion and is not left unmatched") {
        for {
          _       <- seed
          tag     <- createTag("upload", 1L)
          preview <- WordService.bulkUploadPreview(tag.id, "haos", WordLanguage.De, WordLanguage.Hu, 1L)
          haosOnly = preview.suggestions.filter(_.token == "haos")
        } yield assertTrue(
          // "haos" is one substitution away from the seeded "Haus" ("u" -> "o").
          haosOnly.map(_.candidate.word.text) == List("Haus"),
          haosOnly.head.distance == 1,
          // Already knows "Haus" translates to "ház" from the seed data.
          haosOnly.head.candidate.translations.map(_.text) == List("ház"),
          !preview.unmatched.contains("haos"),
        )
      },
      test("a token too far from any dictionary word of the same length stays unmatched") {
        for {
          _       <- seed
          tag     <- createTag("upload", 1L)
          preview <- WordService.bulkUploadPreview(tag.id, "zzzzz", WordLanguage.De, WordLanguage.Hu, 1L)
        } yield assertTrue(
          // "zzzzz" is the same length as the seeded "hauen" but every character differs — distance 5, past the
          // distance-2 bound, so it is offered as a candidate by length but rejected by EditDistance.within.
          preview.suggestions.forall(_.token != "zzzzz"),
          preview.unmatched.contains("zzzzz"),
        )
      },
      test("a token shorter than the minimum suggestion length is never offered a suggestion") {
        for {
          _       <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "ab", rank = 1))
          tag     <- createTag("upload", 1L)
          preview <- WordService.bulkUploadPreview(tag.id, "ac", WordLanguage.De, WordLanguage.Hu, 1L)
        } yield assertTrue(
          // "ac" is one substitution from "ab", but at length 2 it is below minSuggestionTokenLength and so never
          // reaches the edit-distance check at all.
          preview.suggestions.isEmpty,
          preview.unmatched == List("ac"),
        )
      },
      test("accepting a suggestion tags it through the ordinary bulkUploadConfirm path, unchanged") {
        for {
          _         <- seed
          tag       <- createTag("upload", 1L)
          preview   <- WordService.bulkUploadPreview(tag.id, "haos", WordLanguage.De, WordLanguage.Hu, 1L)
          suggestion = preview.suggestions.find(_.token == "haos").get
          selection  =
            BulkUploadSelectedTranslation(suggestion.candidate.word.id, suggestion.candidate.translations.head.wordId)
          added     <-
            WordService.bulkUploadConfirm(
              tag.id,
              WordLanguage.De,
              WordLanguage.Hu,
              List(suggestion.candidate.word.id),
              List(selection),
              Nil,
              Nil,
              1L,
            )
          dePage    <- list(reader = Some(1L), tagId = Some(tag.id))
        } yield assertTrue(added == 2, dePage.items.map(_.word.text) == List("Haus"))
      },
      test(
        "a suggestion is never offered for a dictionary word another token in the same upload already exact-matched"
      ) {
        for {
          _       <- seed
          tag     <- createTag("upload", 1L)
          preview <- WordService.bulkUploadPreview(tag.id, "Haus haos", WordLanguage.De, WordLanguage.Hu, 1L)
        } yield assertTrue(
          // "Haus" exact-matches; "haos" would otherwise suggest that same "Haus", so instead it falls to unmatched.
          preview.matched.map(_.word.text).contains("Haus"),
          preview.suggestions.forall(_.token != "haos"),
          preview.unmatched.contains("haos"),
        )
      },
    ).provide(layer)
  }

  /** Word forms (plural/tense/declension) as `WordService.list`/`.detail` expose them — the lemma/variant columns and
    * the "bring the lemma along as context" search mechanic. Except where a test deliberately searches the variant's
    * own spelling, the fixture words below share no prefix with `Haus`/`hau...` even after accent-folding, so a search
    * for one never incidentally matches the other — every assertion below about which rows a search returns is
    * exercising the form-context machinery, not an accidental prefix collision.
    */
  private def wordFormsSpec = {
    suite("word forms")(
      test("searching a variant's spelling returns its own row and the lemma as context, without inflating total") {
        for {
          _      <- seed
          hausId <- list(search = Some("haus")).map(_.items.head.word.id)
          form   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Häuser", rank = Int.MaxValue))
          _      <- WordRepository.insertForms(List(WordFormRow(0L, hausId, form.id, "plural", 0L)))
          page   <- list(search = Some("häuser"))
        } yield assertTrue(
          // One real match ("Häuser"); "Haus" rides along as context and does not count toward it.
          page.total == 1L,
          page.items.map(_.word.text) == List("Haus", "Häuser"),
          page.items.head.isContext,
          !page.items(1).isContext,
          page.items(1).mainWord.exists(ref => ref.word.text == "Haus" && ref.relation == "plural"),
          page.items.head.variants.exists(v => v.word.text == "Häuser" && v.matched),
        )
      },
      test(
        "a lemma already on the page from its own match is not duplicated as context, but its matched variant is " +
          "still starred"
      ) {
        for {
          _      <- seed
          hausId <- list(search = Some("hau")).map(_.items.find(_.word.text == "Haus").get.word.id)
          form   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Gebäude", rank = Int.MaxValue))
          _      <- WordRepository.insertForms(List(WordFormRow(0L, hausId, form.id, "plural", 0L)))
          page   <- list(search = Some("hau"))
        } yield assertTrue(
          // Same three matches as the plain `seed` search — "Gebäude" itself never matches the "hau" prefix, and no
          // context row was added for a lemma that is already on the page in its own right.
          page.total == 3L,
          page.items.count(_.word.text == "Haus") == 1,
          page.items.find(_.word.text == "Haus").get.variants.exists(v => v.word.text == "Gebäude" && !v.matched),
        )
      },
      test("a variant's detail page names its lemma and the relation, and carries no forms of its own") {
        for {
          _      <- seed
          hausId <- list(search = Some("haus")).map(_.items.head.word.id)
          form   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Häuser", rank = Int.MaxValue))
          _      <- WordRepository.insertForms(List(WordFormRow(0L, hausId, form.id, "plural", 0L)))
          detail <- WordService.detail(form.id, None)
        } yield assertTrue(
          detail.mainWords.map(ref => (ref.word.text, ref.relation)) == List(("Haus", "plural")),
          detail.forms.isEmpty,
        )
      },
      test("a lemma's detail page lists its forms, each carrying the reader's own tags on that form word") {
        for {
          _      <- seed
          hausId <- list(search = Some("haus")).map(_.items.head.word.id)
          form   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Häuser", rank = Int.MaxValue))
          _      <- WordRepository.insertForms(List(WordFormRow(0L, hausId, form.id, "plural", 0L)))
          tag    <- createTag("lesson1", 1L)
          _      <- WordService.tagWord(form.id, tag.id, 1L)
          mine   <- WordService.detail(hausId, Some(1L))
          seen   <- WordService.detail(hausId, None)
        } yield assertTrue(
          mine.forms.map(entry => (entry.word.text, entry.relation)) == List(("Häuser", "plural")),
          // The tick on a form is that form word's own tag, not the lemma's — Haus itself carries none here.
          mine.forms.head.tagIds == List(tag.id),
          mine.tags.isEmpty,
          seen.forms.head.tagIds.isEmpty,
          mine.mainWords.isEmpty,
        )
      },
      test("wordFormsPerRow caps a listing row's variants, but the detail page carries every one of them") {
        for {
          _      <- seed
          hausId <- list(search = Some("haus")).map(_.items.head.word.id)
          forms  <- ZIO.foreach((1 to WordService.wordFormsPerRow + 2).toList)(n =>
                      WordRepository.ensureWord(dictionaryWord(WordLanguage.De, s"Formx$n", rank = Int.MaxValue))
                    )
          _      <- WordRepository.insertForms(forms.map(w => WordFormRow(0L, hausId, w.id, "plural", 0L)))
          page   <- list(search = Some("haus"))
          detail <- WordService.detail(hausId, None)
        } yield {
          val hausRow = page.items.find(_.word.text == "Haus").get
          assertTrue(
            hausRow.variants.size == WordService.wordFormsPerRow,
            hausRow.variantsTotal == forms.size,
            detail.forms.size == forms.size,
          )
        }
      },
    ).provide(layer)
  }

  private def tagCreationSpec = {
    suite("tag creation with pairs")(
      test("creates a tag and records both-direction pairs for existing words") {
        for {
          _     <- seed
          haus  <- WordRepository.ensureWord(dictionaryWord(WordLanguage.De, "Haus", gender = Some(Gender.Das)))
          haz   <- WordRepository.ensureWord(dictionaryWord(WordLanguage.Hu, "haz"))
          tag   <- createTagWithPairs(
                     "lesson1",
                     List(TagPairInput(TagPairWord.Existing(haus.id), TagPairWord.Existing(haz.id))),
                     1L,
                   )
          pairs <- WordRepository.countPairsOwnedBy(1L)
          words <- WordRepository.countWordsInTag(tag.id)
        } yield assertTrue(
          tag.name == "lesson1",
          tag.ownedByMe,
          pairs == 2L,
          words == 2L,
        )
      },
      test("a missing Existing word id is NotFound and writes nothing") {
        for {
          before <- WordRepository.countTagsOwnedBy(1L)
          result <- WordService
                      .createTagWithPairs(
                        "lesson1",
                        List(TagPairInput(TagPairWord.Existing(9999L), TagPairWord.Existing(9999L))),
                        1L,
                      )
                      .either
          after  <- WordRepository.countTagsOwnedBy(1L)
        } yield assertTrue(
          result == Left(WordFailure.NotFound),
          before == after,
        )
      },
      test("a duplicate name is a DuplicateTag") {
        for {
          _     <- createTagWithPairs("lesson1", Nil, 1L)
          clash <- WordService.createTagWithPairs("Lesson1", Nil, 1L).either
        } yield assertTrue(clash == Left(WordFailure.DuplicateTag))
      },
      test("ensures a brand-new word on the fly and pairs it both ways") {
        for {
          tag   <- createTagWithPairs(
                     "lesson1",
                     List(
                       TagPairInput(
                         TagPairWord.New(WordLanguage.De, "Hund", PartOfSpeech.Noun, Some(Gender.Der)),
                         TagPairWord.New(WordLanguage.Hu, "kutya", PartOfSpeech.Noun, None),
                       )
                     ),
                     1L,
                   )
          found <- WordRepository.findWord(
                     WordLanguage.code(WordLanguage.De),
                     "hund",
                     PartOfSpeech.code(PartOfSpeech.Noun),
                     Gender.toColumn(Some(Gender.Der)),
                   )
        } yield assertTrue(
          tag.name == "lesson1",
          found.isDefined,
          found.exists(_.text == "Hund"),
        )
      },
      test("a New word earlier in the list is not created when a later pair is NotFound") {
        for {
          result <- WordService
                      .createTagWithPairs(
                        "lesson1",
                        List(
                          TagPairInput(
                            TagPairWord.New(WordLanguage.De, "Katze", PartOfSpeech.Noun, Some(Gender.Die)),
                            TagPairWord.New(WordLanguage.Hu, "macska", PartOfSpeech.Noun, None),
                          ),
                          TagPairInput(TagPairWord.Existing(9999L), TagPairWord.Existing(9999L)),
                        ),
                        1L,
                      )
                      .either
          katze  <- WordRepository.findWord(
                      WordLanguage.code(WordLanguage.De),
                      "katze",
                      PartOfSpeech.code(PartOfSpeech.Noun),
                      Gender.toColumn(Some(Gender.Die)),
                    )
          macska <- WordRepository.findWord(
                      WordLanguage.code(WordLanguage.Hu),
                      "macska",
                      PartOfSpeech.code(PartOfSpeech.Noun),
                      Gender.toColumn(None),
                    )
          tags   <- WordRepository.countTagsOwnedBy(1L)
        } yield assertTrue(
          result == Left(WordFailure.NotFound),
          katze.isEmpty,
          macska.isEmpty,
          tags == 0L,
        )
      },
    ).provide(layer)
  }

  def spec = {
    suite("WordService (SQLite)")(
      coreSpec,
      quotaSpec,
      tagCreationSpec,
      bulkUploadSpec,
      suggestionsSpec,
      wordFormsSpec,
    ) @@ TestAspect.timeout(60.seconds)
  }
}
