package webapp1.backend.service

import webapp1.backend.config.MicrosoftSection
import webapp1.shared.domain.OAuthProvider
import zio.test._

import java.nio.charset.StandardCharsets
import java.util.Base64

/** The claim checks that stand in for a signature check.
  *
  * `MicrosoftOAuthClient` decodes the id_token payload without verifying the JWS signature, which OIDC Core 1.0
  * §3.1.3.7 permits only because the token arrives over a direct back-channel TLS call to the token endpoint. That
  * makes `iss`, `aud` and `exp` the entire remaining defence, so each one is pinned here — including that a token
  * failing any of them is rejected rather than shrugged off.
  */
object MicrosoftOAuthClientSpec extends ZIOSpecDefault {

  private val clientId = "client-id-under-test"
  private val tenantGuid = "11111111-2222-3333-4444-555555555555"

  private def config(tenant: String): MicrosoftSection = {
    MicrosoftSection(clientId, "secret", "http://localhost:8080/api/auth/microsoft/callback", tenant)
  }

  private def encode(json: String): String = {
    Base64.getUrlEncoder.withoutPadding.encodeToString(json.getBytes(StandardCharsets.UTF_8))
  }

  /** A syntactically real compact JWS: header and signature are present but never inspected, so their contents are
    * arbitrary — which is precisely the property the scaladoc warns about.
    */
  private def token(claims: String): String = {
    s"${encode("""{"alg":"RS256","typ":"JWT"}""")}.${encode(claims)}.not-a-real-signature"
  }

  private val now = 1_700_000_000L

  private def claims(
    iss: String = s"https://login.microsoftonline.com/$tenantGuid/v2.0",
    aud: String = clientId,
    exp: Long = now + 3600,
    sub: String = "subject-abc",
    extra: String = ""","email":"person@example.com"""",
  ): String = {
    s"""{"iss":"$iss","aud":"$aud","exp":$exp,"sub":"$sub"$extra}"""
  }

  private def identityFrom(claimsJson: String, tenant: String = "common") = {
    MicrosoftOAuthClient.identityFrom(token(claimsJson), config(tenant), now)
  }

  def spec = {
    suite("MicrosoftOAuthClient id_token claims")(
      test("a well-formed token yields the subject and email") {
        val result = identityFrom(claims())
        assertTrue(
          result.map(_.subject) == Right("subject-abc"),
          result.map(_.email) == Right("person@example.com"),
          result.map(_.provider) == Right(OAuthProvider.Microsoft),
        )
      },
      // Microsoft asserts no email_verified claim at all, so reporting it as verified would be inventing
      // a fact. Nothing matches on email, so the flag is display metadata only.
      test("emailVerified is false, because Microsoft never claims otherwise") {
        assertTrue(identityFrom(claims()).map(_.emailVerified) == Right(false))
      },
      test("a work account with only preferred_username still yields an email") {
        val result = identityFrom(claims(extra = ""","preferred_username":"worker@contoso.com""""))
        assertTrue(result.map(_.email) == Right("worker@contoso.com"))
      },
      test("a token with neither email nor preferred_username is rejected") {
        assertTrue(errorOf(identityFrom(claims(extra = ""))).contains("neither an email nor a preferred_username"))
      },
      // Without this check any Microsoft app registration's token would be accepted here — the classic
      // audience-confusion bug.
      test("a token minted for a different application is rejected") {
        assertTrue(errorOf(identityFrom(claims(aud = "some-other-app"))).contains("audience mismatch"))
      },
      test("an expired token is rejected") {
        assertTrue(errorOf(identityFrom(claims(exp = now - 1))).contains("has expired"))
      },
      test("a token expiring exactly now is rejected, since exp is not inclusive") {
        assertTrue(errorOf(identityFrom(claims(exp = now))).contains("has expired"))
      },
      test("an issuer from another host is rejected even under the multi-tenant 'common' setting") {
        val result = identityFrom(claims(iss = "https://login.evil.example.com/tenant/v2.0"))
        assertTrue(errorOf(result).contains("is not accepted"))
      },
      // `common` cannot pin the tenant — the issuer carries whichever tenant actually minted the token —
      // so any Microsoft-hosted issuer passes, and pinning is what a GUID tenant is for.
      test("under 'common' any Microsoft-hosted tenant issuer is accepted") {
        val other = "https://login.microsoftonline.com/99999999-8888-7777-6666-555555555555/v2.0"
        assertTrue(identityFrom(claims(iss = other)).isRight)
      },
      test("under a GUID tenant a different tenant's issuer is rejected") {
        val other = "https://login.microsoftonline.com/99999999-8888-7777-6666-555555555555/v2.0"
        assertTrue(errorOf(identityFrom(claims(iss = other), tenantGuid)).contains("is not accepted"))
      },
      test("a token that is not three dot-separated parts is rejected") {
        val result = MicrosoftOAuthClient.identityFrom("not.a-jwt", config("common"), now)
        assertTrue(errorOf(result).contains("not a three-part JWS"))
      },
      test("a payload that is not JSON is rejected") {
        val result = MicrosoftOAuthClient.identityFrom(token("this is not json"), config("common"), now)
        assertTrue(errorOf(result).contains("id_token payload"))
      },
    )
  }

  private def errorOf(result: Either[Throwable, OAuthIdentity]): String = {
    result.fold(_.getMessage, identity => s"unexpectedly succeeded with $identity")
  }
}
