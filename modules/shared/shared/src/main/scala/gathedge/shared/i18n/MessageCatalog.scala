package gathedge.shared.i18n

import gathedge.shared.domain.Locale

/** One language's messages, plus the substitution rules for turning a [[MessageRef]] into prose.
  *
  * Cross-compiled deliberately. The two platforms *load* a catalog differently — the browser fetches
  * `/locales/messages.<code>.json`, the backend reads the same file off its classpath — but they must *render* one
  * identically, because the same `MessageRef` is produced by shared `Validation` code and can be resolved on either
  * side. Duplicating the placeholder logic per platform is exactly how the two would drift.
  *
  * @param entries
  *   flat key → template map, straight from the JSON file. A missing key resolves to the key itself rather than
  *   throwing or rendering empty: a visible `auth.rateLimited` in the UI is a bug report, whereas a blank space is a
  *   mystery. `MessagesSpec` is what stops that reaching anyone.
  */
final case class MessageCatalog(locale: Locale, entries: Map[String, String]) {

  def contains(key: String): Boolean = entries.contains(key)

  def get(key: String): Option[String] = entries.get(key)

  /** Resolves a key, substituting `args` for its `{0}`, `{1}`, … placeholders. */
  def apply(key: String, args: String*): String = {
    MessageCatalog.substitute(entries.getOrElse(key, key), args.toList)
  }

  /** Resolves a server-minted message, expanding any argument that is itself a catalog key. */
  def resolve(ref: MessageRef): String = {
    MessageCatalog.substitute(entries.getOrElse(ref.key, ref.key), ref.args.map(expandArg))
  }

  /** A count-sensitive message, looked up as `baseKey.one` / `baseKey.other`, with the count as `{0}` and any extra
    * arguments following it.
    *
    * The selection is per-language and not a generic CLDR rule, because the two languages here disagree in the one way
    * that matters: English needs "1 session" against "5 sessions", while Hungarian takes the singular after *any*
    * numeral — "1 munkamenet", "5 munkamenet". The codebase's old `"session(s)"` idiom cannot express either properly.
    *
    * Both keys must exist in every catalog even where a language only ever uses one of them; that is what keeps the
    * key-set comparison in `MessagesSpec` a simple equality. Adding a language forces this match to be extended, which
    * is the right place to have to think about it.
    */
  def plural(baseKey: String, count: Long, args: String*): String = {
    val suffix = {
      locale match {
        case Locale.En =>
          if (count == 1) ".one" else ".other"
        case Locale.Hu =>
          ".other"
      }
    }
    apply(baseKey + suffix, (count.toString +: args)*)
  }

  private def expandArg(arg: String): String = {
    if (arg.startsWith(MessageRef.keyArgPrefix)) {
      val key = arg.substring(MessageRef.keyArgPrefix.length)
      entries.getOrElse(key, key)
    } else {
      arg
    }
  }
}

object MessageCatalog {

  /** Replaces `{0}`, `{1}`, … in `template` with the corresponding entry of `args`.
    *
    * One left-to-right pass, so a substituted value containing something that looks like a placeholder is never
    * re-scanned — otherwise an argument whose own text is `{0}` would splice itself back into the sentence. An index
    * with no argument, and any other brace content, is left exactly as written: that shape means the catalog and the
    * call site disagree, and showing the raw `{2}` makes it obvious which.
    */
  def substitute(template: String, args: List[String]): String = {
    if (args.isEmpty || !template.contains("{")) {
      template
    } else {
      val out   = new StringBuilder(template.length)
      var index = 0
      while (index < template.length) {
        val ch = template.charAt(index)
        if (ch == '{') {
          val close  = template.indexOf('}', index + 1)
          val digits = {
            if (close > index + 1) template.substring(index + 1, close) else ""
          }
          val slot   = {
            if (digits.nonEmpty && digits.forall(_.isDigit)) digits.toIntOption else None
          }
          slot.flatMap(args.lift) match {
            case Some(value) =>
              out.append(value)
              index = close + 1
            case None        =>
              out.append(ch)
              index += 1
          }
        } else {
          out.append(ch)
          index += 1
        }
      }
      out.toString
    }
  }
}
