package gathedge.shared.dto

import zio.json.*

/** A freshly minted transfer code, shown to its owner **once**.
  *
  * The code is the bearer credential for a guest account — anyone holding it is that account — so it is treated the way
  * a session id is: never returned a second time, never logged, and rendered by the page with a warning that it will
  * not be shown again. Minting a new one does not invalidate the old ones; a reader may want the same vocabulary on a
  * phone and a laptop.
  */
final case class ClaimCodeResponse(code: String) derives JsonCodec

/** Signs the caller into the guest account a transfer code belongs to. */
final case class ClaimRequest(code: String) derives JsonCodec

/** Turns the guest account the caller is signed in as into a real one.
  *
  * Nothing is copied: the address and the password land on the same row, so every tag, word and translation the guest
  * built up is still there afterwards under the same id. `captchaToken` is required when captcha is configured — this
  * creates a real account and mints an address, the same surface as signup, and it is rendered by the same form.
  */
final case class UpgradeRequest(email: String, password: String, captchaToken: Option[String] = None) derives JsonCodec
