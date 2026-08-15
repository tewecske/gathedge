package gathedge.backend.service

import gathedge.backend.{RecordingEmailSender, TestAuthLayers, TestDataSource}
import gathedge.backend.db.{
  AuditLogRepository,
  EmailVerificationTokenRepository,
  GuestClaimCodeRepository,
  LoginAttemptRepository,
  OAuthIdentityRepository,
  PasswordResetTokenRepository,
  SessionRepository,
  UserRepository,
  WordRepository,
}
import gathedge.backend.i18n.Messages
import gathedge.backend.security.PasswordHasher
import gathedge.shared.dto.{CreateWordRequest, Paging}
import gathedge.shared.domain.{PartOfSpeech, Theme, WordLanguage}
import zio._
import zio.test._

/** Guest accounts end to end: minted without credentials, carried to a second machine by a transfer code, and turned
  * into a real account without losing anything.
  *
  * The vocabulary is here too, because it is the point of the whole arrangement — what has to survive an upgrade is the
  * words, and asserting on the account alone would prove nothing.
  */
object GuestAccountSpec extends ZIOSpecDefault {

  private val repoLayers = {
    TestDataSource.sqlite >>> (
      UserRepository.test ++ SessionRepository.test ++ OAuthIdentityRepository.test ++
        EmailVerificationTokenRepository.test ++ PasswordResetTokenRepository.test ++ LoginAttemptRepository.test ++
        GuestClaimCodeRepository.test ++ AuditLogRepository.test ++ WordRepository.test
    )
  }

  private val layer = {
    (
      repoLayers ++ PasswordHasher.live ++ RateLimiter.live ++ RecordingEmailSender.live ++ Messages.live ++
        TestAuthLayers.configWith(requireEmailVerification = false)
    ) >+> (AuthService.live ++ WordService.live)
  }

  private def tagAWord(userId: Long, text: String): ZIO[WordService, WordFailure, Long] = {
    for {
      tag    <- WordService.createTag("lesson1", userId).map(_.tag)
      detail <- WordService.create(
                  CreateWordRequest(WordLanguage.De, text, PartOfSpeech.Noun, None, Nil, List(tag.id)),
                  userId,
                )
    } yield detail.word.id
  }

  private def myWords(userId: Long) = {
    WordService.list(
      page = Paging.firstPage,
      pageSize = 20,
      language = Some(WordLanguage.De),
      search = None,
      partOfSpeech = None,
      tagId = None,
      mine = true,
      target = WordLanguage.Hu,
      sort = None,
      descending = false,
      reader = Some(userId),
    )
  }

  def spec = {
    suite("Guest accounts (SQLite)")(
      test("a guest is an ordinary account with a session and no address") {
        for {
          minted        <- AuthService.createGuest(Some("10.0.0.1"))
          (guest, token) = minted
          current       <- AuthService.currentUser(token)
        } yield assertTrue(
          guest.isGuest,
          guest.email.isEmpty,
          !guest.isAdmin,
          current.map(_.id).contains(guest.id),
        )
      },
      test("a transfer code signs the same account in somewhere else, and stays usable") {
        for {
          minted    <- AuthService.createGuest(Some("10.0.0.2"))
          (guest, _) = minted
          word      <- tagAWord(guest.id, "Fahrrad")
          code      <- AuthService.issueClaimCode(guest.id)
          first     <- AuthService.claimGuest(code, Some("10.0.0.3"))
          second    <- AuthService.claimGuest(code, Some("10.0.0.4"))
          onSecond  <- myWords(first._1.id)
        } yield assertTrue(
          // The same account, not a copy of it.
          first._1.id == guest.id,
          second._1.id == guest.id,
          // Two devices, two sessions.
          first._2 != second._2,
          onSecond.items.map(_.word.id) == List(word),
        )
      },
      test("minting a guest seeds it with the visitor's current theme rather than a hardcoded default") {
        for {
          minted <- AuthService.createGuest(Some("10.0.0.9"), theme = Theme.Dark)
        } yield assertTrue(minted._1.theme == Theme.Dark)
      },
      test("asking for the transfer code again answers the same one, not a fresh one") {
        for {
          minted    <- AuthService.createGuest(Some("10.0.0.10"))
          (guest, _) = minted
          first     <- AuthService.issueClaimCode(guest.id)
          second    <- AuthService.issueClaimCode(guest.id)
        } yield assertTrue(first == second)
      },
      test("an unknown, mistyped or revoked code answers the same failure") {
        for {
          minted  <- AuthService.createGuest(Some("10.0.0.5"))
          code    <- AuthService.issueClaimCode(minted._1.id)
          unknown <- AuthService.claimGuest("ZZZZ-ZZZZ-ZZZZ-ZZZZ", Some("10.0.0.6")).either
          // Crockford folding: what somebody reads as an l or an O is the 1 and the 0 that were printed.
          folded  <- AuthService.claimGuest(code.toLowerCase.replace('1', 'l').replace('0', 'o'), Some("10.0.0.7"))
          _       <- AuthService.upgradeGuest(minted._1.id, "upgraded@example.com", "password123")
          revoked <- AuthService.claimGuest(code, Some("10.0.0.8")).either
        } yield assertTrue(
          unknown == Left(GuestClaimFailure.InvalidCode),
          folded._1.id == minted._1.id,
          revoked == Left(GuestClaimFailure.InvalidCode),
        )
      },
      test("upgrading keeps every word, under the same account") {
        for {
          minted    <- AuthService.createGuest(Some("10.0.1.1"))
          (guest, _) = minted
          word      <- tagAWord(guest.id, "Schlüssel")
          upgraded  <- AuthService.upgradeGuest(guest.id, "keeper@example.com", "password123")
          after     <- myWords(upgraded.id)
          signedIn  <- AuthService.login("keeper@example.com", "password123")
        } yield assertTrue(
          upgraded.id == guest.id,
          !upgraded.isGuest,
          upgraded.email.contains("keeper@example.com"),
          after.items.map(_.word.id) == List(word),
          signedIn._1.id == guest.id,
        )
      },
      test("a taken address is refused, and a real account has nothing to upgrade") {
        for {
          _        <- AuthService.signup("taken@example.com", "password123")
          minted   <- AuthService.createGuest(Some("10.0.1.2"))
          conflict <- AuthService.upgradeGuest(minted._1.id, "taken@example.com", "password123").either
          weak     <- AuthService.upgradeGuest(minted._1.id, "fine@example.com", "short").either
          real     <- AuthService.signup("real@example.com", "password123")
          notGuest <- AuthService.upgradeGuest(real._1.id, "other@example.com", "password123").either
          noCode   <- AuthService.issueClaimCode(real._1.id).either
        } yield assertTrue(
          conflict == Left(GuestAccountFailure.EmailAlreadyRegistered),
          weak.isLeft,
          notGuest == Left(GuestAccountFailure.NotGuest),
          noCode == Left(GuestCodeFailure.NotGuest),
        )
      },
      // The rule the rest of `AuthService` follows, applied to the two anonymous guest paths: sharing a namespace is
      // what once let a burst of failures on one path lock every account out of another.
      test("minting guests spends its own budget, not sign-in's") {
        for {
          _       <- AuthService.signup("burst@example.com", "password123")
          _       <-
            ZIO.foreachDiscard(1 to RateLimiter.maxAttempts + 1)(_ => AuthService.createGuest(Some("10.0.2.1")).either)
          blocked <- AuthService.createGuest(Some("10.0.2.1")).either
          // The same address can still sign in, which is the half that used to break.
          login   <- AuthService.login("burst@example.com", "password123", Some("10.0.2.1")).either
        } yield assertTrue(blocked == Left(GuestMintFailure.RateLimited), login.isRight)
      },
      test("redeeming a code spends its own budget too") {
        for {
          _       <- ZIO.foreachDiscard(1 to RateLimiter.maxAttempts + 1)(_ =>
                       AuthService.claimGuest("ZZZZ-ZZZZ-ZZZZ-ZZZZ", Some("10.0.3.1")).either
                     )
          blocked <- AuthService.claimGuest("ZZZZ-ZZZZ-ZZZZ-ZZZZ", Some("10.0.3.1")).either
          minted  <- AuthService.createGuest(Some("10.0.3.2")).either
        } yield assertTrue(blocked == Left(GuestClaimFailure.RateLimited), minted.isRight)
      },
    ).provide(layer) @@ TestAspect.timeout(120.seconds) @@ TestAspect.sequential
  }
}
