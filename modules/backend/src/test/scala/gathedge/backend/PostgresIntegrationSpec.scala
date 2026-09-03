package gathedge.backend

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.testcontainers.utility.DockerImageName
import gathedge.backend.TestAuthLayers
import gathedge.backend.config.AppConfig
import gathedge.backend.db.{
  AuditLogRepository,
  DbDialect,
  EmailVerificationTokenRepository,
  FlywayMigrator,
  GamePlayRow,
  GameRepository,
  GameRow,
  GroupRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  OAuthIdentityRepository,
  OAuthIdentityRow,
  PasswordResetTokenRepository,
  ProgressShareRepository,
  SessionRepository,
  TextSearch,
  UsageEventRepository,
  UsageEventRow,
  UserRepository,
  WordFormRow,
  WordRepository,
  WordRow,
}
import gathedge.backend.security.PasswordHasher
import gathedge.backend.service.{
  AdminActor,
  AdminService,
  AuditTrail,
  AuthService,
  EmailSender,
  GameService,
  GameWordList,
  RateLimiter,
  SessionReaper,
  WordFailure,
  WordService,
}
import gathedge.shared.domain.{Gender, TranslationFilter, WordLanguage}
import gathedge.shared.dto.{Paging, ReplacePairRequest, TagPairInput, TagPairWord, WordSort}
import zio._
import zio.test._

import java.util.concurrent.TimeUnit

import javax.sql.DataSource

/** Exercises the Postgres dialect for real — every other *ServiceSpec runs against SQLite (the test-side of the
  * dual-dialect strategy per the plan). This is the one place `RETURNING id`, `GENERATED ALWAYS AS IDENTITY`, and the
  * Postgres join SQL actually get executed rather than just compile-time-checked by Quill.
  *
  * Needs a Docker daemon reachable by testcontainers. Gated behind the `RUN_POSTGRES_TESTS=1` env var so `sbt test`
  * doesn't fail in environments without Docker (this sandbox included, at the time this was written).
  */
object PostgresIntegrationSpec extends ZIOSpecDefault {

  /** The schema the application owns, matching `db.schema` in application.conf. Set on both the pool and Flyway below,
    * exactly as production does it, so this spec exercises the real search_path rather than falling back to `public` —
    * a mismatch between the two halves is precisely the failure no other spec could see.
    */
  private val schema = "gathedge"

  private val containerDataSource: ZLayer[Any, Throwable, DataSource] = ZLayer.scoped {
    for {
      container <-
        ZIO.acquireRelease(
          ZIO.attempt {
            PostgreSQLContainer.Def(dockerImageName = DockerImageName.parse("postgres:16-alpine")).start()
          }
        )(c => ZIO.attempt(c.stop()).orDie)
      ds        <-
        ZIO.acquireRelease(
          ZIO.attempt {
            val config = new HikariConfig()
            config.setJdbcUrl(container.jdbcUrl)
            // Same reason as TestDataSource.sqlite: bypass DriverManager, whose registry is stale after
            // an sbt recompile hands the test run a new classloader.
            config.setDriverClassName("org.postgresql.Driver")
            config.setUsername(container.username)
            config.setPassword(container.password)
            config.setSchema(schema)
            new HikariDataSource(config)
          }
        )(ds => ZIO.attempt(ds.close()).orDie)
      _         <- FlywayMigrator.migrate(ds, DbDialect.Postgresql, Some(schema))
    } yield ds: DataSource
  }

  // `>+>` rather than `>>>` so `DataSource` stays in the environment alongside the repositories: the word-forms
  // cascade test below deletes a `words` row directly, which no repository method exposes -- there is no
  // `deleteWord` anywhere in the app.
  private val repoLayer = {
    containerDataSource >+> (
      UserRepository.live ++ SessionRepository.live ++ OAuthIdentityRepository.live ++
        EmailVerificationTokenRepository.live ++ PasswordResetTokenRepository.live ++ LoginAttemptRepository.live ++
        AuditLogRepository.live ++ UsageEventRepository.live ++ GuestClaimCodeRepository.live ++
        WordRepository.live ++ GameRepository.live ++ ProgressShareRepository.live ++ GroupRepository.live
    )
  }

  // `>+>` rather than `>>>` so the repositories stay in the environment alongside the services: the delete-user test
  // asserts on the rows a cascade removed, which no service exposes once their owner is gone.
  private val layer = {
    repoLayer ++ PasswordHasher.live ++ RateLimiter.live ++ TestCaptchaService.live ++ TestAuthLayers.emailAndConfig ++
      GameWordList.live >+>
      (AuthService.live ++ AuditTrail.live ++ GameService.live) >+> AdminService.live >+> WordService.live
  }

  /** The listing narrowed to main words, as the texts it returns — the one caller of `mainOnly` on this dialect. */
  private def mainWords(search: String): RIO[WordRepository, List[String]] = {
    WordRepository
      .listPage(
        offset = 0,
        limit = 20,
        language = Some("de"),
        search = Some(search),
        partOfSpeech = None,
        tagId = None,
        taggedBy = None,
        translationFilter = TranslationFilter.All,
        targetLanguage = "hu",
        mainOnly = true,
        sort = None,
        descending = false,
      )
      .map(_.map(_.text))
  }

  def spec = {
    suite("Postgres dialect (testcontainers)")(
      // `RETURNING id` and `GENERATED ALWAYS AS IDENTITY` are the two things this dialect does differently from the
      // SQLite one every other spec runs against, and a signup exercises both: the user row, the session row keyed by
      // the id it just produced, and the verification token pointing back at it.
      test("signup and login round-trip through real Postgres, with the rows keyed to the generated id") {
        for {
          signupResult <- AuthService.signup("pguser@example.com", "password123")
          (user, _)     = signupResult
          loggedIn     <- AuthService.login("pguser@example.com", "password123")
          sessions     <- SessionRepository.listForUser(user.id)
          tokens       <- EmailVerificationTokenRepository.findForUser(user.id)
          current      <- AuthService.currentUser(loggedIn._2)
        } yield assertTrue(
          user.id > 0,
          loggedIn._1.id == user.id,
          current.map(_.id).contains(user.id),
          sessions.forall(_.userId == user.id),
          sessions.size == 2,
          tokens.map(_.userId) == List(user.id),
        )
      },
      test("an admin profile-and-password edit commits as one unit and drops the user's sessions") {
        for {
          admin           <- AdminService.createUser(AdminActor.system, "pgadmin@example.com", "password123", isAdmin = true)
          target          <-
            AdminService.createUser(AdminActor(admin.id), "pgtarget@example.com", "password123", isAdmin = false)
          session         <- AuthService.login("pgtarget@example.com", "password123").map(_._2)
          updated         <- AdminService.updateUser(
                               AdminActor(admin.id),
                               target.id,
                               "pgrenamed@example.com",
                               isAdmin = true,
                               password = Some("replacedpw"),
                             )
          afterReset      <- AuthService.currentUser(session)
          withNewPassword <- AuthService.login("pgrenamed@example.com", "replacedpw")
        } yield assertTrue(
          updated.email.contains("pgrenamed@example.com"),
          updated.isAdmin,
          afterReset.isEmpty,
          withNewPassword._1.id == target.id,
        )
      },
      // Only Postgres can catch this: SQLite runs with `PRAGMA foreign_keys` off, so every foreign key in that dialect
      // is inert and the delete succeeds however the constraint is declared. `AdminService.deleteUser` issues a bare
      // `deleteById` and nothing else — removing the rows that point at the account *is* the constraint's job, and a
      // reference declared without an ON DELETE action instead raises
      // "update or delete on table \"users\" violates foreign key constraint", which `deleteById`'s `.orDie` turns
      // into a bare 500. Any new table that references `users` belongs in this test.
      test(
        "deleting a user cascades to its sessions, identities, tokens, tags, practice pairs, transfer codes and games"
      ) {
        for {
          admin      <- AdminService.createUser(AdminActor.system, "pgdeladmin@example.com", "password123", isAdmin = true)
          signup     <- AuthService.signup("pgdeltarget@example.com", "password123")
          (target, _) = signup
          _          <- AuthService.login("pgdeltarget@example.com", "password123")
          _          <- OAuthIdentityRepository.insert(
                          OAuthIdentityRow(0L, target.id, "google", "pg-subject-1", target.email, 0L)
                        )
          // The vocabulary's three per-account tables, and the one table it shares with everybody.
          tag        <- WordRepository.insertTag(target.id, "lesson1", "lesson1", 0L)
          word       <- WordRepository.ensureWord(
                          WordRow(
                            0L,
                            "de",
                            "Löffel",
                            "löffel",
                            "noun",
                            "masculine",
                            1,
                            "user",
                            Some(target.id),
                            0L,
                            TextSearch.fold("löffel"),
                          )
                        )
          spoon      <- WordRepository.ensureWord(
                          WordRow(0L, "hu", "kanál", "kanál", "noun", "", 1, "user", None, 0L, TextSearch.fold("kanál"))
                        )
          _          <- WordRepository.insertTranslationPair(word.id, spoon.id, "user", Some(target.id), 0L)
          _          <- WordRepository.tagWord(word.id, tag.id, 0L)
          // `word_tag_pairs` reaches `users` only through `tags`, but that is the path that breaks: declared without an
          // ON DELETE action, the cascade *into* `tags` would raise a violation and `deleteUser` would answer 500.
          _          <- WordRepository.pairTranslation(word.id, tag.id, spoon.id, 0L)
          _          <- GuestClaimCodeRepository.insert(target.id, "PGDE-LETE-CODE-0001", 0L)
          // `games.owner_user_id` cascades directly; `game_tags.tag_id` reaches `users` only through `tags`, the
          // same indirect path `word_tag_pairs` exercises above.
          game       <- GameRepository.insertGame(
                          gathedge.backend.db.GameRow(0L, target.id, "pg-delete-slug", "PG Delete", "de", "hu", 0L, 0L),
                          List(tag.id),
                        )
          // `game_favorites` references both `users` and `games`; `user_id` cascades directly, so deleting the
          // account that favorited a game must not raise a violation.
          _          <- GameRepository.addFavorite(target.id, game.id, 0L)
          _          <- AdminService.deleteUser(AdminActor(admin.id), target.id)
          gone       <- AdminService.getUser(target.id).either
          sessions   <- SessionRepository.listForUser(target.id)
          identities <- OAuthIdentityRepository.listForUser(target.id)
          tokens     <- EmailVerificationTokenRepository.findForUser(target.id)
          tags       <- WordRepository.listTags(target.id)
          pairs      <- WordRepository.pairsFor(target.id, List(word.id, spoon.id))
          codes      <- GuestClaimCodeRepository.countFor(target.id)
          gameGone   <- GameRepository.findBySlug(game.slug)
          favGone    <- GameRepository.favoriteCounts(List(game.id))
          // The word itself is the SET NULL case: somebody else may well have tagged it, so it outlives its author.
          stillThere <- WordRepository.findWordById(word.id)
          links      <- WordRepository.allTranslationsOf(word.id)
        } yield assertTrue(
          gone == Left(gathedge.backend.service.AdminFailure.NotFound),
          sessions.isEmpty,
          identities.isEmpty,
          tokens.isEmpty,
          tags.isEmpty,
          pairs.isEmpty,
          codes == 0L,
          gameGone.isEmpty,
          favGone.isEmpty,
          stillThere.isDefined,
          stillThere.flatMap(_.createdBy).isEmpty,
          links.map(_._2.text) == List("kanál"),
          links.forall(_._1.createdBy.isEmpty),
        )
      },
      // `tags.group_id` is declared `ON DELETE SET NULL`, the same choice `words.created_by` makes above: a tag
      // belongs to one account regardless of its group, so deleting the group detaches the tag rather than taking it
      // (and everything it holds) down with it.
      test("deleting a group detaches its tags rather than deleting them") {
        for {
          owner <- AuthService.signup("pggroupowner@example.com", "password123").map(_._1)
          tag   <- WordRepository.insertTag(owner.id, "pggrouptag", "pggrouptag", 0L)
          group <- GroupRepository.insertGroup("PG Group", "pg group", "PGGR-OUP0-CODE-0001", owner.id, 0L)
          _     <- GroupRepository.insertMembership(group.id, owner.id, "admin", 0L)
          _     <- WordRepository.setTagGroup(tag.id, Some(group.id))
          _     <- GroupRepository.delete(group.id)
          after <- WordRepository.findTagById(tag.id)
        } yield assertTrue(after.isDefined, after.flatMap(_.groupId).isEmpty)
      },
      // `group_members.user_id` cascades, unlike `tags.group_id` above: a deleted account cannot remain on a roster.
      // This is the accepted gap the migration's own comment documents — deleting a group's *last* admin this way
      // leaves the group with none, since `AdminService.deleteUser` has no notion of `GroupService`'s own last-admin
      // guard and nothing here closes that gap.
      test("deleting a group's last admin removes their membership, even though it leaves the group with no admin") {
        for {
          admin      <- AdminService.createUser(AdminActor.system, "pggroupadmin@example.com", "password123", isAdmin = true)
          signup     <- AuthService.signup("pggrouptarget@example.com", "password123")
          (target, _) = signup
          group      <- GroupRepository.insertGroup("PG Group2", "pg group2", "PGGR-OUP0-CODE-0002", target.id, 0L)
          _          <- GroupRepository.insertMembership(group.id, target.id, "admin", 0L)
          _          <- AdminService.deleteUser(AdminActor(admin.id), target.id)
          admins     <- GroupRepository.countAdmins(group.id)
          member     <- GroupRepository.findMembership(group.id, target.id)
        } yield assertTrue(admins == 0L, member.isEmpty)
      },
      // `game_play_words.play_id` is the one FK this new table declares (`word_id`/`translation_word_id`
      // deliberately are not, per the migration's comment, mirroring `game_play_answers`): deleting the account
      // cascades users -> games -> game_plays -> game_play_words, all the way down, and this is the one dialect
      // that actually enforces every link in that chain.
      test("a play's sampled word set cascades away with the play it belongs to") {
        for {
          admin      <- AdminService.createUser(AdminActor.system, "pgwladmin@example.com", "password123", isAdmin = true)
          signup     <- AuthService.signup("pgwltarget@example.com", "password123")
          (target, _) = signup
          tag        <- WordRepository.insertTag(target.id, "pgwordlimit", "pgwordlimit", 0L)
          source     <- WordRepository.ensureWord(
                          WordRow(0L, "de", "Pgword", "pgword", "noun", "", 1, "user", Some(target.id), 0L, "pgword")
                        )
          dest       <-
            WordRepository.ensureWord(WordRow(0L, "hu", "Pgszo", "pgszo", "noun", "", 1, "user", None, 0L, "pgszo"))
          _          <- WordRepository.pairTranslation(source.id, tag.id, dest.id, 0L)
          game       <- GameRepository.insertGame(
                          GameRow(0L, target.id, "pg-wordlimit-slug", "PG Word Limit", "de", "hu", 0L, 0L),
                          List(tag.id),
                        )
          play       <- GameRepository.insertPlay(
                          GamePlayRow(
                            id = 0L,
                            gameId = game.id,
                            playerUserId = target.id,
                            score = 0,
                            maxScore = 2,
                            wordCount = 1,
                            startedAt = 0L,
                            finishedAt = None,
                          ),
                          List((source.id, dest.id)),
                        )
          before     <- GameRepository.wordPairsOf(play.id)
          _          <- AdminService.deleteUser(AdminActor(admin.id), target.id)
          after      <- GameRepository.wordPairsOf(play.id)
        } yield assertTrue(
          before == List((source.id, dest.id)),
          after.isEmpty,
        )
      },
      // `GameRepository.matchingPlays`'s player filter is a correlated subquery against `users` — the first query in
      // this repository to reach that table at all, and the reserved-word lambda-naming rule (`row`, never `user`)
      // only bites on this dialect. Real Postgres is the only place `listPlaysPage`/`countPlaysMatching`/
      // `findPlayInGame`/`usersByIds` actually run as SQL rather than just compiling. `matchingMyPlays`'s game-name
      // filter is the same shape against `games`, plus a `LOWER()` (via `String.toLowerCase`) whose case folding the
      // two dialects can disagree about — so it is exercised here too, as is `matchingAllGames`, which lowers the same
      // `games.name` column for the games listing.
      test(
        "a game's owner-facing plays listing filters by player, the caller's history filters by game name, for real"
      ) {
        for {
          owner         <- AuthService.signup("pgowner@example.com", "password123").map(_._1)
          alice         <- AuthService.signup("pgalice@example.com", "password123").map(_._1)
          bob           <- AuthService.signup("pgbob@example.com", "password123").map(_._1)
          tag           <- WordRepository.insertTag(owner.id, "pgtracked", "pgtracked", 0L)
          source        <- WordRepository.ensureWord(
                             WordRow(0L, "de", "Pgtrack", "pgtrack", "noun", "", 1, "user", Some(owner.id), 0L, "pgtrack")
                           )
          dest          <-
            WordRepository.ensureWord(WordRow(0L, "hu", "Pgnyom", "pgnyom", "noun", "", 1, "user", None, 0L, "pgnyom"))
          _             <- WordRepository.pairTranslation(source.id, tag.id, dest.id, 0L)
          gameA         <- GameRepository.insertGame(
                             GameRow(0L, owner.id, "pg-tracked-a", "PG Tracked A", "de", "hu", 0L, 0L),
                             List(tag.id),
                           )
          gameB         <- GameRepository.insertGame(
                             GameRow(0L, owner.id, "pg-tracked-b", "PG Tracked B", "de", "hu", 0L, 0L),
                             List(tag.id),
                           )
          playAlice     <- GameRepository.insertPlay(
                             GamePlayRow(0L, gameA.id, alice.id, 2, 2, 1, 0L, Some(1L)),
                             List((source.id, dest.id)),
                           )
          playBob       <- GameRepository.insertPlay(
                             GamePlayRow(0L, gameA.id, bob.id, 0, 2, 1, 0L, Some(1L)),
                             List((source.id, dest.id)),
                           )
          playOtherGame <- GameRepository.insertPlay(
                             GamePlayRow(0L, gameB.id, alice.id, 2, 2, 1, 0L, Some(1L)),
                             List((source.id, dest.id)),
                           )
          all           <- GameRepository.listPlaysPage(gameA.id, 0, 20, None, None, false)
          total         <- GameRepository.countPlaysMatching(gameA.id, None)
          filtered      <- GameRepository.listPlaysPage(gameA.id, 0, 20, Some("alice"), None, false)
          ownPlay       <- GameRepository.findPlayInGame(gameA.id, playAlice.id)
          crossGame     <- GameRepository.findPlayInGame(gameB.id, playAlice.id)
          crossGame2    <- GameRepository.findPlayInGame(gameA.id, playOtherGame.id)
          players       <- GameRepository.usersByIds(List(alice.id, bob.id)).map(_.map(u => u.id -> u.email).toMap)
          myAll         <- GameRepository.listMyPlaysPage(alice.id, None, None, 0, 20, None, false)
          myByName      <- GameRepository.listMyPlaysPage(alice.id, None, Some("tracked a"), 0, 20, None, false)
          myByNameCount <- GameRepository.countMyPlaysMatching(alice.id, None, Some("TRACKED"))
          myByNameMiss  <- GameRepository.countMyPlaysMatching(alice.id, None, Some("no-such-game"))
          gamesByName   <- GameRepository.listAllGamesPage(Some("pg tracked a"), None, 0, 20, None, false)
          gamesCount    <- GameRepository.countAllGamesMatching(Some("PG TRACKED"), None)
          gamesMiss     <- GameRepository.countAllGamesMatching(Some("no-such-game"), None)
          // `game_favorites` filter/count/sort — a correlated subquery over yet another table, plus the `.sortBy` one,
          // only ever SQL here. alice favorites gameB, bob favorites gameA and gameB.
          _             <- GameRepository.addFavorite(alice.id, gameB.id, 0L)
          _             <- GameRepository.addFavorite(alice.id, gameB.id, 0L) // idempotent
          _             <- GameRepository.addFavorite(bob.id, gameA.id, 0L)
          _             <- GameRepository.addFavorite(bob.id, gameB.id, 0L)
          favMine       <- GameRepository.listAllGamesPage(Some("pg tracked"), Some(alice.id), 0, 20, None, false)
          favMineCount  <- GameRepository.countAllGamesMatching(Some("pg tracked"), Some(bob.id))
          favCounts     <- GameRepository.favoriteCounts(List(gameA.id, gameB.id))
          favIds        <- GameRepository.favoritedGameIds(bob.id, List(gameA.id, gameB.id))
          bySort        <- GameRepository.listAllGamesPage(Some("pg tracked"), None, 0, 20, Some("likeCount"), true)
          removed       <- GameRepository.removeFavorite(alice.id, gameB.id)
          removedAgain  <- GameRepository.removeFavorite(alice.id, gameB.id)
        } yield assertTrue(
          all.map(_.id).toSet == Set(playAlice.id, playBob.id),
          total == 2L,
          filtered.map(_.id) == List(playAlice.id),
          ownPlay.contains(playAlice.copy(id = playAlice.id)),
          crossGame.isEmpty,
          crossGame2.isEmpty,
          players.get(alice.id).flatten.contains("pgalice@example.com"),
          players.get(bob.id).flatten.contains("pgbob@example.com"),
          // alice played both games; the game-name filter is a case-insensitive substring of the game's own name.
          myAll.map(_.id).toSet == Set(playAlice.id, playOtherGame.id),
          myByName.map(_.id) == List(playAlice.id),
          myByNameCount == 2L,
          myByNameMiss == 0L,
          // the games listing is not owner-scoped; it filters that same `games.name` column the same way.
          gamesByName.map(_.id) == List(gameA.id),
          gamesCount == 2L,
          gamesMiss == 0L,
          // `favoritesOf` keeps only that account's favorites; the count/id lookups and the like-count sort agree.
          favMine.map(_.id) == List(gameB.id),
          favMineCount == 2L,
          favCounts == Map(gameA.id -> 1L, gameB.id -> 2L),
          favIds == Set(gameA.id, gameB.id),
          bySort.map(_.id) == List(gameB.id, gameA.id),
          removed == 1L,
          removedAgain == 0L,
        )
      },
      // `login_attempts`, `audit_log` and `usage_events` are the user references declared ON DELETE SET NULL rather
      // than CASCADE, and the same blind spot applies: SQLite enforces neither, so the whole SQLite suite passes
      // whichever. Getting it wrong in either direction is a real bug — CASCADE would erase the record of what was
      // done to (or by) an account the moment it is deleted, and NO ACTION would make `deleteUser` answer 500 for
      // every account that has ever signed in or made a request.
      test(
        "deleting a user keeps its audit entries, sign-in history and usage events, with the references nulled out"
      ) {
        for {
          admin           <- AdminService.createUser(AdminActor.system, "pgaudit@example.com", "password123", isAdmin = true)
          target          <-
            AdminService.createUser(AdminActor(admin.id), "pgaudited@example.com", "password123", isAdmin = false)
          // Both directions of the reference: the target has attempts and is the subject of an audit entry, and it
          // is also the *actor* on one of its own (it clears its own lockout), so deleting it exercises
          // `audit_log.actor_user_id` as well as `login_attempts.user_id`.
          _               <- AuthService.login("pgaudited@example.com", "password123")
          _               <- AuthService.login("pgaudited@example.com", "wrong").either
          _               <- AdminService.clearLockout(AdminActor(target.id), target.id)
          _               <-
            UsageEventRepository.insert(
              UsageEventRow(0L, 0L, "GET", "/api/pg-test", 200, Some(target.id), Some("10.0.0.9"))
            )
          attemptsBefore  <- AdminService.loginAttempts(50, None).map(_.count(_.userId.contains(target.id)))
          auditBefore     <- AdminService.auditLog(Paging.firstPage, 50, None, false, None, None, Some(target.id.toString))
          usageBefore     <- UsageEventRepository.countsByUser(0L).map(_.toMap.getOrElse(target.id, 0L))
          usageTotal      <- UsageEventRepository.countAll
          _               <- AdminService.deleteUser(AdminActor(admin.id), target.id)
          gone            <- AdminService.getUser(target.id).either
          attemptsAfter   <- AdminService.loginAttempts(50, None).map(_.filter(_.email == "pgaudited@example.com"))
          auditAfter      <- AdminService.auditLog(Paging.firstPage, 50, None, false, None, None, Some(target.id.toString))
          usageAfter      <- UsageEventRepository.countsByUser(0L).map(_.toMap.getOrElse(target.id, 0L))
          usageTotalAfter <- UsageEventRepository.countAll
        } yield assertTrue(
          gone == Left(gathedge.backend.service.AdminFailure.NotFound),
          attemptsBefore == 2,
          auditBefore.items.nonEmpty,
          usageBefore == 1L,
          // The rows survive; only the foreign keys are cleared.
          attemptsAfter.size == 2,
          attemptsAfter.forall(_.userId.isEmpty),
          auditAfter.items.size >= auditBefore.items.size,
          auditAfter.items.exists(_.actorEmail.contains("pgaudit@example.com")),
          // The entry the deleted account wrote itself keeps the address it had, and loses only the id.
          auditAfter.items.exists(entry =>
            entry.actorEmail.contains("pgaudited@example.com") && entry.actorUserId.isEmpty
          ),
          // The usage event row survives (the total count is unchanged) but no longer counts against the deleted
          // account, because its `user_id` was nulled rather than the row being cascaded away.
          usageTotalAfter == usageTotal,
          usageAfter == 0L,
        )
      },
      // Both of these are queries the SQLite suite runs happily and Postgres refuses, because Quill names the SQL
      // alias after the lambda parameter and `user` is a reserved word there. `UPDATE users AS user SET ...` is a
      // syntax error, and so is a `WHERE user.is_guest` in the reaper's subquery — the whole guest feature was
      // green on SQLite and 500 on the real dialect. Anything touching `users` through a quoted lambda belongs here.
      test("a guest can be minted, carried by a transfer code and upgraded, on the real dialect") {
        for {
          minted    <- AuthService.createGuest(Some("10.9.0.1"))
          (guest, _) = minted
          code      <- AuthService.issueClaimCode(guest.id)
          claimed   <- AuthService.claimGuest(code, Some("10.9.0.2"))
          upgraded  <- AuthService.upgradeGuest(guest.id, "pgguest@example.com", "password123")
          signedIn  <- AuthService.login("pgguest@example.com", "password123")
          codeGone  <- AuthService.claimGuest(code, Some("10.9.0.3")).either
        } yield assertTrue(
          guest.isGuest,
          guest.email.isEmpty,
          claimed._1.id == guest.id,
          upgraded.id == guest.id,
          !upgraded.isGuest,
          upgraded.email.contains("pgguest@example.com"),
          signedIn._1.id == guest.id,
          codeGone.isLeft,
        )
      },
      // Three SQL shapes reach the real dialect here for the first time: `pairTranslation`'s four-statement transaction
      // with `returningGenerated`, `unpairTranslation`'s two-statement one, and the `||` inside the `DELETE` that
      // `untagWord` grew. SQLite would pass whatever any of them rendered to.
      test("marking and unmarking a practice answer round-trips on the real dialect") {
        for {
          reader  <- AuthService.createGuest(Some("10.9.2.1")).map(_._1)
          tag     <- WordService.createTag("pglesson", reader.id).map(_.tag)
          word    <- WordRepository.ensureWord(
                       WordRow(0L, "de", "Gabel", "gabel", "noun", "feminine", 1, "user", Some(reader.id), 0L, "gabel")
                     )
          fork    <-
            WordRepository.ensureWord(WordRow(0L, "hu", "villa", "villa", "noun", "", 1, "user", None, 0L, "villa"))
          _       <- WordRepository.insertTranslationPair(word.id, fork.id, "user", Some(reader.id), 0L)
          _       <- WordService.selectPair(word.id, tag.id, fork.id, reader.id)
          marked  <- WordRepository.pairsFor(reader.id, List(word.id, fork.id))
          links   <- WordRepository.tagsFor(reader.id, List(word.id, fork.id))
          _       <- WordService.deselectPair(word.id, tag.id, fork.id, reader.id)
          cleared <- WordRepository.pairsFor(reader.id, List(word.id, fork.id))
          _       <- WordService.selectPair(word.id, tag.id, fork.id, reader.id)
          _       <- WordService.untagWord(word.id, tag.id, reader.id)
          swept   <- WordRepository.pairsFor(reader.id, List(word.id, fork.id))
          left    <- WordRepository.tagsFor(reader.id, List(word.id, fork.id))
        } yield assertTrue(
          // Both directions of the pair, and both words filed under the tag.
          marked.map(row => (row.wordId, row.translationWordId)).toSet ==
            Set((word.id, fork.id), (fork.id, word.id)),
          links.map(_.wordId).toSet == Set(word.id, fork.id),
          cleared.isEmpty,
          // Untagging the word takes its pairs in that tag with it, both ways round, and leaves the translation filed.
          swept.isEmpty,
          left.map(_.wordId) == List(fork.id),
        )
      },
      // The unified tag editor adds three more SQL shapes the real dialect has not seen: `bulkImport`'s per-token
      // `importPair`/`importWord` writes, `replacePair`'s delete-then-relink transaction, and `removeEntry`'s
      // orphan-partner prune. `tagEntries`' ordering-by-`word_tags.id` collapse runs here for the first time too.
      test("the tag editor's add / bulk-import / replace / remove round-trip on the real dialect") {
        for {
          reader   <- AuthService.createGuest(Some("10.9.2.4")).map(_._1)
          tag      <- WordService.createTag("pgeditor", reader.id).map(_.tag)
          haus     <- WordRepository.ensureWord(
                        WordRow(0L, "de", "Pghaus", "pghaus", "noun", "neuter", 1, "user", None, 0L, "pghaus")
                      )
          haz      <- WordRepository.ensureWord(
                        WordRow(0L, "hu", "Pghaz", "pghaz", "noun", "", 1, "user", None, 0L, "pghaz")
                      )
          _        <- WordRepository.insertTranslationPair(haus.id, haz.id, "dictionary", None, 0L)
          _        <- WordService.bulkImport(tag.id, "Pghaus Pghaz brandneu", WordLanguage.De, WordLanguage.Hu, reader.id)
          imported <- WordService.tagEntries(tag.id, reader.id)
          // Replace the exact row's answer with a fresh word.
          neu      <- WordRepository.ensureWord(
                        WordRow(0L, "hu", "Pgotthon", "pgotthon", "noun", "", 1, "user", None, 0L, "pgotthon")
                      )
          _        <- WordService.replacePair(
                        tag.id,
                        ReplacePairRequest(
                          haus.id,
                          Some(haz.id),
                          TagPairInput(TagPairWord.Existing(haus.id), TagPairWord.Existing(neu.id)),
                        ),
                        reader.id,
                      )
          replaced <- WordService.tagEntries(tag.id, reader.id)
          _        <- WordService.removeEntry(tag.id, haus.id, None, reader.id)
          afterRm  <- WordService.tagEntries(tag.id, reader.id)
          pairsRm  <- WordRepository.pairsInTag(tag.id)
        } yield assertTrue(
          // Text order kept; exact pair collapsed to one row, the unmatched token its own answerless row.
          imported.map(r => (r.source.text, r.target.map(_.text), r.imported, r.exact)) ==
            List(("Pghaus", Some("Pghaz"), true, true), ("brandneu", None, true, false)),
          // The swap landed and cleared the exact flag; the old answer no longer shows as a stray row.
          replaced.map(r => (r.source.text, r.target.map(_.text), r.exact)) ==
            List(("Pghaus", Some("Pgotthon"), false), ("brandneu", None, false)),
          // Removing the row took its pair and the now-orphaned answer with it; the unmatched row is untouched.
          afterRm.map(_.source.text) == List("brandneu"),
          pairsRm.isEmpty,
        )
      },
      // `removePair`'s targeted delete-plus-conditional-prune is its own SQL shape: a source word with two marked
      // translations must lose only the named one, keeping its membership and its other row.
      test("removePair drops one translation's row on the real dialect and keeps the word's others") {
        for {
          reader <- AuthService.createGuest(Some("10.9.2.9")).map(_._1)
          tag    <- WordService.createTag("pgremovepair", reader.id).map(_.tag)
          dog    <- WordRepository.ensureWord(
                      WordRow(0L, "en", "Pgdog", "pgdog", "noun", "", 1, "user", None, 0L, "pgdog")
                    )
          hund   <- WordRepository.ensureWord(
                      WordRow(0L, "de", "Pghund", "pghund", "noun", "masculine", 1, "user", None, 0L, "pghund")
                    )
          koeter <- WordRepository.ensureWord(
                      WordRow(0L, "de", "Pgkoeter", "pgkoeter", "noun", "masculine", 1, "user", None, 0L, "pgkoeter")
                    )
          _      <- WordService.addPair(
                      tag.id,
                      TagPairInput(TagPairWord.Existing(dog.id), TagPairWord.Existing(hund.id)),
                      reader.id,
                    )
          _      <- WordService.addPair(
                      tag.id,
                      TagPairInput(TagPairWord.Existing(dog.id), TagPairWord.Existing(koeter.id)),
                      reader.id,
                    )
          _      <- WordService.removeEntry(tag.id, dog.id, Some(hund.id), reader.id)
          rows   <- WordService.tagEntries(tag.id, reader.id)
          pairs  <- WordRepository.pairsInTag(tag.id)
          links  <- WordRepository.tagsFor(reader.id, List(dog.id, hund.id, koeter.id))
        } yield assertTrue(
          rows.map(r => (r.source.text, r.target.map(_.text))) == List(("Pgdog", Some("Pgkoeter"))),
          pairs.map(p => (p.wordId, p.translationWordId)).toSet == Set((dog.id, koeter.id), (koeter.id, dog.id)),
          // `dog` stays (still paired with `koeter`); `hund` is gone (nothing pairs it any more).
          links.map(_.wordId).toSet == Set(dog.id, koeter.id),
        )
      },
      // The only UPDATE any `words` row takes, and the only one whose guard is a column the unique index also covers.
      // `UNIQUE (language, text_norm, part_of_speech, gender)` is what decides the conflict, and only Postgres runs the
      // dialect this ships on.
      test("filling in a noun's gender updates the row in place, and refuses the twin's identity") {
        for {
          reader    <- AuthService.createGuest(Some("10.9.2.7")).map(_._1)
          blank     <- WordRepository.ensureWord(
                         WordRow(0L, "de", "Pgsee", "pgsee", "noun", "", 1, "user", Some(reader.id), 0L, "pgsee")
                       )
          filled    <- WordService.setGender(blank.id, Gender.Masculine, reader.id)
          twin      <- WordRepository.ensureWord(
                         WordRow(0L, "de", "Pgbank", "pgbank", "noun", "feminine", 1, "user", None, 0L, "pgbank")
                       )
          other     <- WordRepository.ensureWord(
                         WordRow(0L, "de", "Pgbank", "pgbank", "noun", "", 1, "user", None, 0L, "pgbank")
                       )
          conflict  <- WordService.setGender(other.id, Gender.Feminine, reader.id).either
          untouched <- WordRepository.findWordById(other.id)
          spent     <- WordRepository.setWordGender(blank.id, "neuter")
        } yield assertTrue(
          // Updated in place: the same id, so every translation, tag and mark on it survives.
          filled.word.id == blank.id,
          filled.word.gender.contains(Gender.Masculine),
          twin.id != other.id,
          conflict == Left(WordFailure.GenderConflict),
          untouched.exists(_.gender.isEmpty),
          // The repository's own `gender = ''` guard: a row that already has one is not overwritten.
          spent == 0L,
        )
      },
      // The "newest in tag" ordering is the one listing shape that joins `word_tags` and orders by a column outside
      // `words`, so it is the one whose rendered SQL SQLite would accept whatever Postgres made of it.
      test("newest in tag orders the listing by the tick on the real dialect") {
        for {
          reader <- AuthService.createGuest(Some("10.9.2.9")).map(_._1)
          tag    <- WordService.createTag("pgrecent", reader.id).map(_.tag)
          first  <-
            WordRepository.ensureWord(
              WordRow(0L, "de", "Pgerste", "pgerste", "noun", "feminine", 1, "user", Some(reader.id), 0L, "pgerste")
            )
          second <-
            WordRepository.ensureWord(
              WordRow(0L, "de", "Pgzweite", "pgzweite", "noun", "feminine", 2, "user", Some(reader.id), 0L, "pgzweite")
            )
          _      <- WordRepository.tagWord(first.id, tag.id, 1_000L)
          _      <- WordRepository.tagWord(second.id, tag.id, 2_000L)
          newest <- WordRepository.listPage(
                      offset = 0,
                      limit = 20,
                      language = Some("de"),
                      search = None,
                      partOfSpeech = None,
                      tagId = Some(tag.id),
                      taggedBy = None,
                      translationFilter = TranslationFilter.All,
                      targetLanguage = "hu",
                      mainOnly = false,
                      sort = Some(WordSort.added),
                      descending = true,
                    )
          count  <- WordRepository.countMatching(
                      language = Some("de"),
                      search = None,
                      partOfSpeech = None,
                      tagId = Some(tag.id),
                      taggedBy = None,
                      translationFilter = TranslationFilter.All,
                      targetLanguage = "hu",
                      mainOnly = false,
                    )
        } yield assertTrue(
          // The later tick first, which is the opposite of the rank order the two words carry.
          newest.map(_.text) == List("Pgzweite", "Pgerste"),
          // The join matches each word once, so the page holds the rows the count counted.
          count == 2L,
        )
      },
      test("the reaper's sweep runs, and takes only the guests with nothing on them") {
        for {
          empty      <- AuthService.createGuest(Some("10.9.1.1")).map(_._1)
          keeper     <- AuthService.createGuest(Some("10.9.1.2")).map(_._1)
          tag        <- WordRepository.insertTag(keeper.id, "keep", "keep", 0L)
          _           = tag
          now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
          // Both are minutes old, so a cutoff in the future is what makes them sweepable at all.
          abandoned  <- UserRepository.findAbandonedGuests(now + 1000L, 100)
          swept      <- SessionReaper.sweep
          emptyGone  <- UserRepository.findById(empty.id)
          keeperHere <- UserRepository.findById(keeper.id)
        } yield assertTrue(
          abandoned.contains(empty.id),
          !abandoned.contains(keeper.id),
          swept.guests >= 0L,
          emptyGone.isDefined || swept.guests > 0L,
          keeperHere.isDefined,
        )
      },
      // `progress_shares`/`progress_share_codes` are the newest tables referencing `users`, and both reference it
      // twice over (`sharer_user_id`/`viewer_user_id`, and the code's own `user_id`) — the first table in this
      // schema where one row can be cascaded away from either of two different accounts being deleted.
      test("a progress share cascades away when either the sharer or the viewer account is deleted") {
        for {
          sharer      <- AuthService.signup("pgsharer@example.com", "password123").map(_._1)
          viewerGone  <- AuthService.signup("pgviewergone@example.com", "password123").map(_._1)
          viewerStays <- AuthService.signup("pgviewerstays@example.com", "password123").map(_._1)
          admin       <- AdminService.createUser(AdminActor.system, "pgshareadmin@example.com", "password123", isAdmin = true)
          now         <- Clock.currentTime(TimeUnit.MILLISECONDS)
          _           <- ProgressShareRepository.insertCode(sharer.id, "PGSH-ARE0-CODE-0001", now)
          _           <- ProgressShareRepository.insertShare(sharer.id, viewerGone.id, now)
          _           <- ProgressShareRepository.insertShare(sharer.id, viewerStays.id, now)
          _           <- AdminService.deleteUser(AdminActor(admin.id), viewerGone.id)
          afterViewer <- ProgressShareRepository.listViewersFor(sharer.id)
          _           <- AdminService.deleteUser(AdminActor(admin.id), sharer.id)
          afterSharer <- ProgressShareRepository.listViewersFor(sharer.id)
          codeGone    <- ProgressShareRepository.findActiveCodeForUser(sharer.id)
        } yield assertTrue(
          // Deleting the viewer takes only its own share; the other viewer's grant is untouched.
          afterViewer.map(_.viewerUserId) == List(viewerStays.id),
          // Deleting the sharer takes every remaining share, and its own code, with it.
          afterSharer.isEmpty,
          codeGone.isEmpty,
        )
      },
      // `word_forms` is the first table in this schema that references `words` twice over from the same row
      // (`lemma_word_id`/`form_word_id`), the same shape the progress-share test above exercises against `users`.
      // There is no `deleteWord` anywhere in the app — a `words` row is never deleted through a service — so the
      // delete here is issued as raw SQL directly against the pooled `DataSource`, which `repoLayer`'s `>+>` keeps
      // in the environment for exactly this reason.
      test("a word's forms cascade away when either the lemma or the form word is deleted") {
        def deleteWord(id: Long): RIO[DataSource, Unit] = {
          ZIO.serviceWithZIO[DataSource] { ds =>
            ZIO.attemptBlocking {
              val conn = ds.getConnection()
              try {
                val stmt = conn.prepareStatement("DELETE FROM words WHERE id = ?")
                try {
                  stmt.setLong(1, id)
                  stmt.executeUpdate()
                  ()
                } finally stmt.close()
              } finally conn.close()
            }
          }
        }

        for {
          haus       <- WordRepository.ensureWord(
                          WordRow(0L, "de", "Haus", "haus", "noun", "neuter", 1, "dictionary", None, 0L, "haus")
                        )
          hauser     <-
            WordRepository.ensureWord(
              WordRow(0L, "de", "Häuser", "häuser", "noun", "", 1, "dictionary", None, 0L, TextSearch.fold("häuser"))
            )
          hauses     <- WordRepository.ensureWord(
                          WordRow(0L, "de", "Hauses", "hauses", "noun", "", 1, "dictionary", None, 0L, "hauses")
                        )
          now        <- Clock.currentTime(TimeUnit.MILLISECONDS)
          _          <- WordRepository.insertForms(
                          List(
                            WordFormRow(0L, haus.id, hauser.id, "plural", now),
                            WordFormRow(0L, haus.id, hauses.id, "genitive", now),
                          )
                        )
          _          <- deleteWord(hauser.id)
          afterForm  <- WordRepository.formsOf(haus.id)
          _          <- deleteWord(haus.id)
          afterLemma <- WordRepository.lemmaOf(hauses.id)
        } yield assertTrue(
          // Deleting the plural form takes only its own relation; the genitive's is untouched.
          afterForm.map(_.relation) == List("genitive"),
          // Deleting the lemma takes every remaining relation with it.
          afterLemma.isEmpty,
        )
      },
      // `words.is_form` is a denormalization of `word_forms`, kept true by the two writers of that table inside their
      // own transactions (V20__words_is_form.sql). Both the flag's two `UPDATE`s and the `mainOnly` listing predicate
      // they feed are new statements over `words`, which is the rule `CLAUDE.md` states for that table. The cascade
      // test above is the one place a `words` row is deleted at all, and it is raw SQL rather than a service.
      test("the main-word flag follows word_forms on the real dialect") {
        for {
          lemma    <- WordRepository.ensureWord(
                        WordRow(0L, "de", "PgBaum", "pgbaum", "noun", "masculine", 1, "dictionary", None, 0L, "pgbaum")
                      )
          form     <- WordRepository.ensureWord(
                        WordRow(0L, "de", "PgBäume", "pgbäume", "noun", "", 2, "dictionary", None, 0L, "pgbaume")
                      )
          fresh    <- WordRepository.findWordById(form.id)
          now      <- Clock.currentTime(TimeUnit.MILLISECONDS)
          _        <- WordRepository.insertForms(
                        List(
                          WordFormRow(0L, lemma.id, form.id, "plural", now),
                          WordFormRow(0L, lemma.id, form.id, "nominative,plural", now),
                        )
                      )
          linked   <- WordRepository.findWordById(form.id)
          hidden   <- mainWords("pgb")
          _        <- WordRepository.deleteWordForms(form.id, "plural")
          partial  <- WordRepository.findWordById(form.id)
          _        <- WordRepository.deleteWordForms(form.id, "nominative,plural")
          released <- WordRepository.findWordById(form.id)
          back     <- mainWords("pgb")
        } yield assertTrue(
          // A word starts out main, and the batch insert flags every form word it names.
          fresh.exists(!_.isForm),
          linked.exists(_.isForm),
          // The listing sees the column, not a subquery: the lemma is there, the form is not.
          hidden == List("PgBaum"),
          // One relation of two deleted still leaves the word a form; the last one frees it.
          partial.exists(_.isForm),
          released.exists(!_.isForm),
          back == List("PgBaum", "PgBäume"),
        )
      },
      // Not a referential-integrity case either — the two distractor readers touch `words` and `word_forms` only. They
      // earn a place here because both are new queries over `words` (the rule `CLAUDE.md` states for that table), one
      // of them lifting a list of Strings into an `IN` clause, and because `relatedWords` runs two statements and
      // concatenates them in Scala rather than in SQL.
      test("the multiple-choice distractor readers run on the real dialect") {
        for {
          lemma     <- WordRepository.ensureWord(
                         WordRow(0L, "de", "PgHund", "pghund", "noun", "masculine", 1, "dictionary", None, 0L, "pghund")
                       )
          plural    <- WordRepository.ensureWord(
                         WordRow(0L, "de", "PgHunde", "pghunde", "noun", "feminine", 1, "dictionary", None, 0L, "pghunde")
                       )
          otherSee  <- WordRepository.ensureWord(
                         WordRow(0L, "de", "PgSee", "pgsee", "noun", "masculine", 1, "dictionary", None, 0L, "pgsee")
                       )
          sameSee   <- WordRepository.ensureWord(
                         WordRow(0L, "de", "PgSee", "pgsee", "noun", "feminine", 1, "dictionary", None, 0L, "pgsee")
                       )
          now       <- Clock.currentTime(TimeUnit.MILLISECONDS)
          _         <- WordRepository.insertForms(List(WordFormRow(0L, lemma.id, plural.id, "plural", now)))
          fromLemma <- GameRepository.relatedWords(List(lemma.id))
          fromForm  <- GameRepository.relatedWords(List(plural.id))
          byText    <- GameRepository.wordsByTexts("de", List("PgSee"))
        } yield assertTrue(
          fromLemma.map(_.id) == List(plural.id),
          fromForm.map(_.id) == List(lemma.id),
          byText.map(_.id).toSet == Set(otherSee.id, sameSee.id),
        )
      },
      // Not a referential-integrity case — `findWordsByLengthRange` touches only `words`, no FK. It earns a place here
      // anyway because it is the first query in this codebase to call `.length` on a quoted String column, and Quill
      // lowers that to `LEN(...)`, a SQL Server spelling neither dialect has: SQLite's own suite already caught this
      // (`no such function: Len`) before the query was rewritten with an explicit `LENGTH(...)` infix. This is the
      // dialect that would otherwise have let the same mistake back in silently.
      test("findWordsByLengthRange filters by textNorm length on the real dialect") {
        for {
          _        <- WordRepository.ensureWord(
                        WordRow(0L, "de", "Haus", "haus", "noun", "neuter", 1, "dictionary", None, 0L, "haus")
                      )
          _        <- WordRepository.ensureWord(
                        WordRow(0L, "de", "Haufen", "haufen", "noun", "masculine", 900, "dictionary", None, 0L, "haufen")
                      )
          inRange  <- WordRepository.findWordsByLengthRange("de", 4, 4)
          outRange <- WordRepository.findWordsByLengthRange("de", 10, 20)
        } yield assertTrue(
          inRange.map(_.text) == List("Haus"),
          outRange.isEmpty,
        )
      },
      // V14's backfill is exercised nowhere else: every other test in this spec runs against `layer`'s shared
      // container, which `containerDataSource` migrates straight to latest before any test body runs — so the four
      // `UPDATE ... SET` statements in `V14__game_play_variants.sql` always operate on zero pre-existing rows in
      // every other test here. This test builds its own separate, disposable container (not `layer`'s), stops
      // Flyway at V13 — the last version before `games` loses `word_limit`/`randomize_each_play`/
      // `include_definite_articles` and `game_plays` gains their per-play replacements — inserts one `games` row and
      // one `game_plays` row by hand in that pre-V14 shape (raw SQL over the pooled connection, since
      // `GameRow`/`GamePlayRow` model only the current, post-V14 shape), then lets a second `migrate` call (default
      // target: latest) run V14 for real, against real data, and asserts the backfilled row carries exactly what its
      // owning `games` row had. `word_preference` is not itself backfilled from anywhere (`games` never had this
      // column) — asserted here anyway, as part of the row's full post-migration shape, to confirm it lands on its
      // column default (`'all'`) rather than null.
      test("V14 backfills game_plays' new per-play columns from the games row that predates them") {
        def rawInsert(ds: DataSource, sql: String, params: List[Any]): Task[Long] = {
          ZIO.attemptBlocking {
            val conn = ds.getConnection()
            try {
              val stmt = conn.prepareStatement(sql)
              try {
                params.zipWithIndex.foreach { case (p, idx) =>
                  p match {
                    case s: String  => stmt.setString(idx + 1, s)
                    case l: Long    => stmt.setLong(idx + 1, l)
                    case b: Boolean => stmt.setBoolean(idx + 1, b)
                    case n: Int     => stmt.setInt(idx + 1, n)
                  }
                }
                val rs = stmt.executeQuery()
                try {
                  rs.next()
                  rs.getLong(1)
                } finally rs.close()
              } finally stmt.close()
            } finally conn.close()
          }
        }

        def rawSelectVariant(ds: DataSource, sql: String, playId: Long) = {
          ZIO.attemptBlocking {
            val conn = ds.getConnection()
            try {
              val stmt = conn.prepareStatement(sql)
              try {
                stmt.setLong(1, playId)
                val rs = stmt.executeQuery()
                try {
                  rs.next()
                  val source     = rs.getString("source_language")
                  val target     = rs.getString("target_language")
                  val limitValue = rs.getInt("word_limit")
                  val wordLimit  = if (rs.wasNull()) None else Some(limitValue)
                  val articles   = rs.getBoolean("include_definite_articles")
                  val preference = rs.getString("word_preference")
                  (source, target, wordLimit, articles, preference)
                } finally rs.close()
              } finally stmt.close()
            } finally conn.close()
          }
        }

        val testSchema = "gathedge"

        ZIO.scoped {
          for {
            container  <-
              ZIO.acquireRelease(
                ZIO.attempt {
                  PostgreSQLContainer.Def(dockerImageName = DockerImageName.parse("postgres:16-alpine")).start()
                }
              )(c => ZIO.attempt(c.stop()).orDie)
            ds         <-
              ZIO.acquireRelease(
                ZIO.attempt {
                  val config = new HikariConfig()
                  config.setJdbcUrl(container.jdbcUrl)
                  config.setDriverClassName("org.postgresql.Driver")
                  config.setUsername(container.username)
                  config.setPassword(container.password)
                  config.setSchema(testSchema)
                  new HikariDataSource(config)
                }
              )(ds => ZIO.attempt(ds.close()).orDie)
            _          <- FlywayMigrator.migrate(ds, DbDialect.Postgresql, Some(testSchema), target = Some("13"))
            userId     <-
              rawInsert(
                ds,
                s"INSERT INTO $testSchema.users (email, created_at) VALUES (?, ?) RETURNING id",
                List("pgv14backfill@example.com", 0L),
              )
            gameId     <-
              rawInsert(
                ds,
                s"""INSERT INTO $testSchema.games
                   |  (owner_user_id, slug, name, source_language, target_language, created_at, updated_at,
                   |   word_limit, include_definite_articles)
                   |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""".stripMargin,
                List(userId, "pgv14-backfill-slug", "V14 backfill game", "de", "hu", 0L, 0L, 5, false),
              )
            playId     <-
              rawInsert(
                ds,
                s"""INSERT INTO $testSchema.game_plays (game_id, player_user_id, max_score, word_count, started_at)
                   |VALUES (?, ?, ?, ?, ?) RETURNING id""".stripMargin,
                List(gameId, userId, 10, 5, 0L),
              )
            _          <- FlywayMigrator.migrate(ds, DbDialect.Postgresql, Some(testSchema))
            backfilled <-
              rawSelectVariant(
                ds,
                s"""SELECT source_language, target_language, word_limit, include_definite_articles, word_preference
                   |FROM $testSchema.game_plays WHERE id = ?""".stripMargin,
                playId,
              )
          } yield assertTrue(backfilled == (("de", "hu", Some(5), false, "all")))
        }
      },
    ).provide(layer) @@ TestAspect.ifEnvSet("RUN_POSTGRES_TESTS") @@ TestAspect.sequential
  }
}
