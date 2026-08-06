package webapp1.shared.domain

import zio.json.*

enum Theme derives JsonCodec {
  case Light,
    Dark
}

object Theme {
  def fromString(s: String): Option[Theme] = {
    s.toLowerCase match {
      case "light" =>
        Some(Theme.Light)
      case "dark"  =>
        Some(Theme.Dark)
      case _       =>
        None
    }
  }
}
