package webapp1.frontend.components

import scala.scalajs.js

/** Rendering for the numbers the administrator screens are full of.
  *
  * Every timestamp the API carries is epoch millis, which is unreadable on a screen; every one of these pages shows
  * several. Formatting goes through the browser's own locale rather than a fixed pattern, so an administrator reads
  * times in their own timezone — which matters, because the question these screens answer is usually "when did this
  * happen relative to when the user says it did".
  */
object Formats {

  def dateTime(millis: Long): String = {
    new js.Date(millis.toDouble).toLocaleString()
  }

  def date(millis: Long): String = {
    new js.Date(millis.toDouble).toLocaleDateString()
  }

  def dateTimeOpt(millis: Option[Long]): String = {
    millis.map(dateTime).getOrElse("—")
  }

  /** `createdAt` on the shared `User` is epoch millis that has already been rendered to a string, which no screen can
    * show as it stands. Anything that does not parse is passed through rather than replaced, so a later change to that
    * field's format degrades to showing the raw value instead of showing nothing.
    */
  def dateTimeFromString(value: String): String = {
    value.toLongOption.map(dateTime).getOrElse(value)
  }

  /** Coarse and deliberately approximate: these are read at a glance, and "3 days" is more use than "3d 4h 12m". */
  def duration(millis: Long): String = {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours   = minutes / 60
    val days    = hours / 24
    if (days > 0)
      s"$days day(s), ${hours % 24} hour(s)"
    else if (hours > 0)
      s"$hours hour(s), ${minutes % 60} minute(s)"
    else if (minutes > 0)
      s"$minutes minute(s)"
    else
      s"$seconds second(s)"
  }

  def minutes(millis: Long): String = {
    val rounded = math.ceil(millis.toDouble / 60000).toLong
    s"$rounded minute(s)"
  }

  def bytes(value: Long): String = {
    val mib = value.toDouble / (1024 * 1024)
    if (mib >= 1024)
      f"${mib / 1024}%.1f GiB"
    else
      f"$mib%.0f MiB"
  }
}
