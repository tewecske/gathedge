package gathedge.frontend.components

import gathedge.frontend.i18n.I18n
import gathedge.shared.domain.Locale.code
import gathedge.shared.i18n.UiKeys

import scala.scalajs.js

/** `js.Date`'s locale-taking overloads, which the Scala.js standard library's facade does not declare — it exposes only
  * the no-argument forms, i.e. "whatever language the browser is set to". That is the wrong answer here: the page a
  * visitor is reading is the one the URL prefix chose, not the one their browser prefers.
  */
@js.native
private trait LocalizedDate extends js.Object {
  def toLocaleString(locales: String): String     = js.native
  def toLocaleDateString(locales: String): String = js.native
}

/** Rendering for the numbers the administrator screens are full of.
  *
  * Every timestamp the API carries is epoch millis, which is unreadable on a screen; every one of these pages shows
  * several. Formatting goes through `toLocaleString` rather than a fixed pattern, with the page's own language as the
  * locale tag — a Hungarian page reads Hungarian dates, the same rule as everything else here. The *timezone* still
  * comes from the browser, which matters, because the question these screens answer is usually "when did this happen
  * relative to when the user says it did".
  */
object Formats {

  /** The page's language as a BCP 47 tag `Intl` understands. `Locale`'s codes are plain ISO 639-1, which is already a
    * valid tag; passing the region as well would be guesswork, and the timezone — the part an administrator actually
    * needs — is the browser's either way.
    */
  private def localeTag: String = I18n.locale.code

  private def at(millis: Long): LocalizedDate = {
    new js.Date(millis.toDouble).asInstanceOf[LocalizedDate]
  }

  def dateTime(millis: Long): String = {
    at(millis).toLocaleString(localeTag)
  }

  def date(millis: Long): String = {
    at(millis).toLocaleDateString(localeTag)
  }

  def dateTimeOpt(millis: Option[Long]): String = {
    millis.map(dateTime).getOrElse(I18n.t(UiKeys.commonNone))
  }

  /** `createdAt` on the shared `User` is epoch millis that has already been rendered to a string, which no screen can
    * show as it stands. Anything that does not parse is passed through rather than replaced, so a later change to that
    * field's format degrades to showing the raw value instead of showing nothing.
    */
  def dateTimeFromString(value: String): String = {
    value.toLongOption.map(dateTime).getOrElse(value)
  }

  /** Coarse and deliberately approximate: these are read at a glance, and "3 days" is more use than "3d 4h 12m".
    *
    * Each unit goes through `I18n.plural` rather than the `"day(s)"` idiom this used to carry — that shape has no
    * Hungarian equivalent, where a numeral is always followed by the singular.
    */
  def duration(millis: Long): String = {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours   = minutes / 60
    val days    = hours / 24
    if (days > 0)
      pair(UiKeys.durationDays, days, UiKeys.durationHours, hours % 24)
    else if (hours > 0)
      pair(UiKeys.durationHours, hours, UiKeys.durationMinutes, minutes % 60)
    else if (minutes > 0)
      I18n.plural(UiKeys.durationMinutes, minutes)
    else
      I18n.plural(UiKeys.durationSeconds, seconds)
  }

  private def pair(coarseKey: String, coarse: Long, fineKey: String, fine: Long): String = {
    I18n.t(UiKeys.durationPair, I18n.plural(coarseKey, coarse), I18n.plural(fineKey, fine))
  }

  def minutes(millis: Long): String = {
    val rounded = math.ceil(millis.toDouble / 60000).toLong
    I18n.plural(UiKeys.formatMinutes, rounded)
  }

  def bytes(value: Long): String = {
    val mib = value.toDouble / (1024 * 1024)
    if (mib >= 1024)
      f"${mib / 1024}%.1f GiB"
    else
      f"$mib%.0f MiB"
  }
}
