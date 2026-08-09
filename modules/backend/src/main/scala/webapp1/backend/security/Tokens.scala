package webapp1.backend.security

import zio.*

import java.security.SecureRandom
import java.util.Base64

/** The one generator for every opaque bearer string this application mints: session ids, email verification tokens,
  * group invitation tokens and the OAuth `state` nonce.
  *
  * '''`zio.Random` is not an alternative here.''' Its live implementation delegates to `scala.util.Random`, i.e. a
  * 48-bit linear congruential generator whose internal state is recoverable from a couple of observed outputs — fine
  * for a shuffle, useless for a value whose only job is to be unguessable. The OAuth nonce was generated with
  * `Random.nextUUID` and was consequently predictable by anyone who could sample a few nonces of their own, which for
  * the callback (the one route with no CSRF header to check) was the whole of its protection.
  *
  * One `SecureRandom` instance is shared: it is thread-safe, and seeding a fresh one per call is both slower and no
  * stronger.
  */
object Tokens {

  private val secureRandom = new SecureRandom()

  /** 32 bytes is the size every caller wanted and the size the `VARCHAR(64)` token columns are cut for: 32 bytes
    * base64url-encode to 43 characters.
    */
  val defaultByteCount = 32

  /** Url-safe and unpadded, so the value can sit in a path segment, a query parameter or a cookie without escaping. */
  def urlSafe(byteCount: Int = defaultByteCount): UIO[String] = {
    ZIO.succeed {
      val bytes = new Array[Byte](byteCount)
      secureRandom.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    }
  }
}
