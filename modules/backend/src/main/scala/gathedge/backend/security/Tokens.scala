package gathedge.backend.security

import zio.*

import java.security.SecureRandom
import java.util.Base64

/** The one generator for every opaque bearer string this application mints: session ids, email verification tokens, and
  * the OAuth `state` nonce.
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

  /** Crockford's base32 alphabet: the digits and the letters, minus `I`, `L`, `O` and `U`. The first three go because
    * they are unreadable next to `1` and `0` in the fonts people copy codes out of; `U` goes so a random string cannot
    * spell an obscenity.
    */
  private val claimAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

  private val claimCodeLength = 16
  private val claimGroupSize  = 4

  /** A transfer code: [[claimCodeLength]] symbols of [[claimAlphabet]] — 80 bits — written in groups of four.
    *
    * Unlike a session id this is meant to be read off one screen and typed into another, hence the alphabet and the
    * grouping; and unlike a session id it is not thrown away when a browser is closed, which is why it takes 80 bits
    * rather than the 40-odd a throwaway would need. `256 % 32 == 0`, so masking a random byte with `0x1f` picks a
    * symbol uniformly and no rejection sampling is needed.
    */
  def claimCode(): UIO[String] = {
    ZIO.succeed {
      val bytes   = new Array[Byte](claimCodeLength)
      secureRandom.nextBytes(bytes)
      val symbols = bytes.map(byte => claimAlphabet((byte & 0x1f).toInt)).mkString
      symbols.grouped(claimGroupSize).mkString("-")
    }
  }

  /** The prefix every minted guest username carries, so a name a reader has not chosen reads as one. */
  private val guestUsernamePrefix = "guest-"

  private val guestUsernameSuffixLength = 6

  /** A guest account's starting username: [[guestUsernamePrefix]] plus [[guestUsernameSuffixLength]] symbols of
    * [[claimAlphabet]], lowercased — 30 bits, which is what makes a collision rare enough for the caller's retry to be
    * a safety net rather than the normal path.
    *
    * '''Not a credential.''' Nothing signs in with a username alone, so this needs no secrecy; it is generated here
    * because this is where the one `SecureRandom` lives, and because a name minted from `scala.util.Random` would
    * repeat across a restart. It satisfies `Validation.validateUsername`, so the reader can keep it or type another.
    */
  def guestUsername(): UIO[String] = {
    ZIO.succeed {
      val bytes   = new Array[Byte](guestUsernameSuffixLength)
      secureRandom.nextBytes(bytes)
      val symbols = bytes.map(byte => claimAlphabet((byte & 0x1f).toInt)).mkString
      guestUsernamePrefix + symbols.toLowerCase
    }
  }

  /** The canonical form of a code somebody has typed back in: upper-cased, regrouped, and with the four characters
    * Crockford treats as confusable folded onto the symbol they are mistaken for.
    *
    * Stored codes are in this form, so a lookup is an equality test rather than a fuzzy match. Anything not in the
    * alphabet is dropped rather than rejected here — a code that is wrong in some other way is answered by the same "no
    * such code" as one that does not exist, so there is nothing to gain by being strict early.
    */
  def normalizeClaimCode(value: String): String = {
    val folded = value.toUpperCase.map {
      case 'I' | 'L' =>
        '1'
      case 'O'       =>
        '0'
      case other     =>
        other
    }
    folded.filter(claimAlphabet.contains).grouped(claimGroupSize).mkString("-")
  }
}
