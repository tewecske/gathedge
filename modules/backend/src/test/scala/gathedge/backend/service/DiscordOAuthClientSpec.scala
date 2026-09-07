package gathedge.backend.service

import gathedge.shared.domain.OAuthProvider
import zio.test._

/** What the `/users/@me` projection has to get right.
  *
  * Discord is plain OAuth2, not OIDC: there is no id_token to decode, so `DiscordOAuthClient.identityFrom` is the whole
  * of the identity mapping. It takes the raw response body, which is why every case here is exercisable without an HTTP
  * stub. The network calls around it (token exchange, the bearer GET) are covered by `OAuthRoutesSpec`'s stub client.
  */
object DiscordOAuthClientSpec extends ZIOSpecDefault {

  private def user(
    id: String = "80351110224678912",
    emailField: String = ""","email":"person@example.com"""",
    verifiedField: String = ""","verified":true""",
  ): String = {
    s"""{"id":"$id","username":"nelly"$emailField$verifiedField}"""
  }

  private def errorOf(result: Either[Throwable, OAuthIdentity]): String = {
    result.fold(_.getMessage, identity => s"unexpectedly succeeded with $identity")
  }

  def spec = {
    suite("DiscordOAuthClient user projection")(
      test("a well-formed user yields the id as subject, the email, and the Discord provider") {
        val result = DiscordOAuthClient.identityFrom(user())
        assertTrue(
          result.map(_.subject) == Right("80351110224678912"),
          result.map(_.email) == Right("person@example.com"),
          result.map(_.provider) == Right(OAuthProvider.Discord),
        )
      },
      test("verified true is carried through") {
        assertTrue(
          DiscordOAuthClient.identityFrom(user(verifiedField = ""","verified":true""")).map(_.emailVerified) == Right(
            true
          )
        )
      },
      test("verified false is carried through") {
        assertTrue(
          DiscordOAuthClient.identityFrom(user(verifiedField = ""","verified":false""")).map(_.emailVerified) == Right(
            false
          )
        )
      },
      // Discord omits the field for an account whose address it has not confirmed; "the provider did not say"
      // reads as unverified.
      test("a missing verified field is treated as unverified") {
        assertTrue(DiscordOAuthClient.identityFrom(user(verifiedField = "")).map(_.emailVerified) == Right(false))
      },
      // The address is display metadata, but an account with none has nothing for the account menu to show.
      test("a user with no email is rejected") {
        assertTrue(errorOf(DiscordOAuthClient.identityFrom(user(emailField = ""))).contains("carries no email"))
      },
      test("a user whose email is an empty string is rejected") {
        val body = """{"id":"1","username":"nelly","email":"","verified":true}"""
        assertTrue(errorOf(DiscordOAuthClient.identityFrom(body)).contains("carries no email"))
      },
      test("a body that is not JSON is rejected") {
        assertTrue(errorOf(DiscordOAuthClient.identityFrom("this is not json")).contains("user response"))
      },
    )
  }
}
