package gathedge.shared.domain

import zio.json.*

/** The social sign-in providers the app knows how to talk to.
  *
  * Lives in `shared` rather than beside the backend client because three places need the same vocabulary: the backend
  * config and routes, the `oauth_identities.provider` column (via [[wireName]]), and the frontend, which renders one
  * sign-in button and one settings row per provider.
  */
enum OAuthProvider derives JsonCodec, CanEqual {
  case Google,
    Microsoft
}

object OAuthProvider {

  /** Lower-case form used in URLs (`/api/auth/google/start`) and stored in `oauth_identities.provider`. Kept explicit
    * rather than derived from `toString` so renaming a case can't silently orphan every stored row.
    */
  def wireName(provider: OAuthProvider): String = {
    provider match {
      case Google    =>
        "google"
      case Microsoft =>
        "microsoft"
    }
  }

  def fromString(s: String): Option[OAuthProvider] = {
    s.toLowerCase match {
      case "google"    =>
        Some(Google)
      case "microsoft" =>
        Some(Microsoft)
      case _           =>
        None
    }
  }

  /** What the sign-in button and the settings row call it. */
  def displayName(provider: OAuthProvider): String = {
    provider match {
      case Google    =>
        "Google"
      case Microsoft =>
        "Microsoft"
    }
  }

  val all: List[OAuthProvider] = List(Google, Microsoft)

  extension (provider: OAuthProvider) {
    def wire: String    = wireName(provider)
    def display: String = displayName(provider)
  }
}
