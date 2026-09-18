package gathedge.backend.service

import gathedge.shared.domain.OAuthProvider
import zio.test._

/** What the `/me` projection has to get right.
  *
  * Facebook is plain OAuth2, not OIDC: there is no id_token to decode, so `FacebookOAuthClient.identityFrom` is the
  * whole of the identity mapping. It takes the raw response body, which is why every case here is exercisable without
  * an HTTP stub. The network calls around it (token exchange, the bearer GET) are covered by `OAuthRoutesSpec`'s stub
  * client.
  */
object FacebookOAuthClientSpec extends ZIOSpecDefault {

  private def user(
    id: String = "10223071087543210",
    emailField: String = ""","email":"person@example.com"""",
  ): String = {
    s"""{"id":"$id","name":"Nelly Nobody"$emailField}"""
  }

  private def errorOf(result: Either[Throwable, OAuthIdentity]): String = {
    result.fold(_.getMessage, identity => s"unexpectedly succeeded with $identity")
  }

  def spec = {
    suite("FacebookOAuthClient user projection")(
      test("a well-formed user yields the app-scoped id as subject, the email, and the Facebook provider") {
        val result = FacebookOAuthClient.identityFrom(user())
        assertTrue(
          result.map(_.subject) == Right("10223071087543210"),
          result.map(_.email) == Right("person@example.com"),
          result.map(_.provider) == Right(OAuthProvider.Facebook),
        )
      },
      // Facebook asserts no `email_verified` equivalent, so the address is never reported as verified — which is
      // what keeps `app.require-email-verification` asking a Facebook account to follow a verification link.
      test("the address is reported unverified, since Facebook asserts nothing about it") {
        assertTrue(FacebookOAuthClient.identityFrom(user()).map(_.emailVerified) == Right(false))
      },
      // The address is display metadata, but an account with none has nothing for the account menu to show.
      test("a user with no email is rejected") {
        assertTrue(errorOf(FacebookOAuthClient.identityFrom(user(emailField = ""))).contains("carries no email"))
      },
      test("a user whose email is an empty string is rejected") {
        val body = """{"id":"1","name":"Nelly Nobody","email":""}"""
        assertTrue(errorOf(FacebookOAuthClient.identityFrom(body)).contains("carries no email"))
      },
      test("a body that is not JSON is rejected") {
        assertTrue(errorOf(FacebookOAuthClient.identityFrom("this is not json")).contains("user response"))
      },
      // The Graph API reports an error object rather than a user for an expired or revoked token; it decodes as
      // neither an id nor an address.
      test("a Graph API error body is rejected") {
        val body = """{"error":{"message":"Invalid OAuth access token.","type":"OAuthException","code":190}}"""
        assertTrue(errorOf(FacebookOAuthClient.identityFrom(body)).contains("user response"))
      },
    )
  }
}
