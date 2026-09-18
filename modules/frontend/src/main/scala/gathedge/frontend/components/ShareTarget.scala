package gathedge.frontend.components

import com.raquo.laminar.api.L._
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.UiKeys

import scala.scalajs.js.URIUtils.encodeURIComponent

/** One place a link can be handed to, through that site's own share page — the buttons [[ShareRow]] draws beside its
  * copy-link, Web Share and QR code.
  *
  * Every case here has a share page that takes the link as a query parameter and needs nothing else of the deployment,
  * [[Messenger]] excepted: its dialog is opened with the Facebook app id, which is why [[available]] decides what the
  * row draws and [[shareUrl]] answers `None` rather than building a URL that would greet the reader with an error page.
  * Discord is deliberately absent — it has no such page, and a link is pasted into a chat instead, which the copy-link
  * button already covers.
  */
enum ShareTarget derives CanEqual {
  case WhatsApp,
    Messenger,
    Telegram,
    Email,
    Facebook,
    X,
    Reddit
}

object ShareTarget {

  /** The row's order: the three that send a link to one person first, then mail, then the three that post it to an
    * audience.
    */
  val all: List[ShareTarget] = List(WhatsApp, Messenger, Telegram, Email, Facebook, X, Reddit)

  /** Brand names, untranslated — the endonym rule the language picker follows. */
  def displayName(target: ShareTarget): String = {
    target match {
      case WhatsApp  =>
        "WhatsApp"
      case Messenger =>
        "Messenger"
      case Telegram  =>
        "Telegram"
      case Email     =>
        "Email"
      case Facebook  =>
        "Facebook"
      case X         =>
        "X"
      case Reddit    =>
        "Reddit"
    }
  }

  /** The button's accessible name and its tooltip. Email is not a brand and reads badly in "Share on …", so it has its
    * own line rather than the shared one with a name in it.
    */
  def label(target: ShareTarget): String = {
    target match {
      case Email =>
        I18n.t(UiKeys.shareEmail)
      case other =>
        I18n.t(UiKeys.shareVia, displayName(other))
    }
  }

  /** What the row draws: everything but [[Messenger]] where no app id came back, since a Messenger button without one
    * could only open a dialog that refuses.
    */
  def available(messengerAppId: Option[String]): List[ShareTarget] = {
    all.filter(target => target != Messenger || messengerAppId.isDefined)
  }

  /** The share page for one target, with `link` and `title` already encoded.
    *
    * `redirect_uri` on Messenger's dialog is the shared link itself: the dialog has to be told where to send the reader
    * afterwards, and the page being shared is ours, so it is a page the Facebook app already knows.
    */
  def shareUrl(
    target: ShareTarget,
    link: String,
    title: String,
    messengerAppId: Option[String],
  ): Option[String] = {
    val url  = encodeURIComponent(link)
    val text = encodeURIComponent(title)
    val both = encodeURIComponent(textWithLink(title, link))
    target match {
      case WhatsApp  =>
        Some(s"https://wa.me/?text=$both")
      case Messenger =>
        messengerAppId.map { appId =>
          s"https://www.facebook.com/dialog/send?app_id=${encodeURIComponent(appId)}&link=$url&redirect_uri=$url"
        }
      case Telegram  =>
        Some(s"https://t.me/share/url?url=$url&text=$text")
      case Email     =>
        Some(s"mailto:?subject=$text&body=$both")
      case Facebook  =>
        Some(s"https://www.facebook.com/sharer/sharer.php?u=$url")
      case X         =>
        Some(s"https://x.com/intent/post?url=$url&text=$text")
      case Reddit    =>
        Some(s"https://www.reddit.com/submit?url=$url&title=$text")
    }
  }

  /** WhatsApp and a mail body take one block of text rather than a title and a URL of their own, so the two are joined.
    * A page whose title has not arrived yet (a group's name comes with its detail request) sends the bare link rather
    * than a link with a space in front of it.
    */
  private def textWithLink(title: String, link: String): String = {
    val trimmed = title.trim
    if (trimmed.isEmpty) link else s"$trimmed $link"
  }

  /** Each brand's own mark, drawn rather than fetched, the same way `OAuthButtons` draws its sign-in icons.
    *
    * The fills are the brands' own colours and stay the same in every theme, again as in `OAuthButtons` — with two
    * exceptions that take `currentColor` instead: X's mark is black, which would disappear on the dark theme, and mail
    * is not a brand at all.
    */
  def icon(target: ShareTarget): SvgElement = {
    target match {
      case WhatsApp  =>
        brandMark(
          "#25d366",
          "M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164" +
            "-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297" +
            "-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075" +
            "-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792" +
            ".372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709" +
            ".306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289" +
            ".173-1.413-.074-.124-.272-.198-.57-.347m-5.421 7.403h-.004a9.87 9.87 0 01-5.031-1.378l-.361-.214-3.741" +
            ".982.998-3.648-.235-.374a9.86 9.86 0 01-1.51-5.26c.001-5.45 4.436-9.884 9.888-9.884 2.64 0 5.122 1.03" +
            " 6.988 2.898a9.825 9.825 0 012.893 6.994c-.003 5.45-4.437 9.884-9.885 9.884m8.413-18.297A11.815 11.815" +
            " 0 0012.05 0C5.495 0 .16 5.335.157 11.892c0 2.096.547 4.142 1.588 5.945L.057 24l6.305-1.654a11.882" +
            " 11.882 0 005.683 1.448h.005c6.554 0 11.89-5.335 11.893-11.893a11.821 11.821 0 00-3.48-8.413Z",
        )
      case Messenger =>
        brandMark(
          "#00b2ff",
          "M.001 11.639C.001 4.949 5.241 0 12.001 0S24 4.95 24 11.639c0 6.689-5.24 11.638-12 11.638-1.21 0-2.38-.16" +
            "-3.47-.46a.96.96 0 00-.64.05l-2.39 1.05a.96.96 0 01-1.35-.85l-.07-2.14a.97.97 0 00-.32-.68A11.39 11.389" +
            " 0 01.002 11.64zm8.32-2.19l-3.52 5.6c-.35.53.32 1.139.82.75l3.79-2.87c.26-.2.6-.2.87 0l2.8 2.1c.84.63" +
            " 2.04.4 2.6-.48l3.52-5.6c.35-.53-.32-1.13-.82-.75l-3.79 2.87c-.25.2-.6.2-.86 0l-2.8-2.1a1.8 1.8 0 00" +
            "-2.61.48z",
        )
      case Telegram  =>
        brandMark(
          "#26a5e4",
          "M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962" +
            " 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36" +
            " 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185" +
            "-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024" +
            "c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245" +
            "-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025" +
            "-1.627 4.476-1.635z",
        )
      case Email     =>
        // Heroicons' solid envelope, the icon set `HelpIcon` and the page chrome already draw from.
        svg.svg(
          svg.cls     := "size-4",
          svg.viewBox := "0 0 24 24",
          svg.fill    := "currentColor",
          svg.path(
            svg.d := "M1.5 8.67v8.58a3 3 0 0 0 3 3h15a3 3 0 0 0 3-3V8.67l-8.928 5.493a3 3 0 0 1-3.144 0L1.5 8.67Z"
          ),
          svg.path(
            svg.d := (
              "M22.5 6.908V6.75a3 3 0 0 0-3-3h-15a3 3 0 0 0-3 3v.158l9.714 5.978a1.5 1.5 0 0 0 1.572 0L22.5 6.908Z"
            )
          ),
        )
      case Facebook  =>
        brandMark(
          "#1877f2",
          "M9.101 23.691v-7.98H6.627v-3.667h2.474v-1.58c0-4.085 1.848-5.978 5.858-5.978.401 0 .955.042 1.468.103a8.68" +
            " 8.68 0 0 1 1.141.195v3.325a8.623 8.623 0 0 0-.653-.036 26.805 26.805 0 0 0-.733-.009c-.707 0-1.259.096" +
            "-1.675.309a1.686 1.686 0 0 0-.679.622c-.258.42-.374.995-.374 1.752v1.297h3.919l-.386 2.103-.287 1.564h" +
            "-3.246v8.245C19.396 23.238 24 18.179 24 12.044c0-6.627-5.373-12-12-12s-12 5.373-12 12c0 5.628 3.874" +
            " 10.35 9.101 11.647Z",
        )
      case X         =>
        brandMark(
          "currentColor",
          "M18.901 1.153h3.68l-8.04 9.19L24 22.846h-7.406l-5.8-7.584-6.638 7.584H.474l8.6-9.83L0 1.154h7.594l5.243" +
            " 6.932ZM17.61 20.644h2.039L6.486 3.24H4.298Z",
        )
      case Reddit    =>
        brandMark(
          "#ff4500",
          "M12 0C5.373 0 0 5.373 0 12c0 3.314 1.343 6.314 3.515 8.485l-2.286 2.286C.775 23.225 1.097 24 1.738 24H12c" +
            "6.627 0 12-5.373 12-12S18.627 0 12 0Zm4.388 3.199c1.104 0 1.999.895 1.999 1.999 0 1.105-.895 2-1.999 2" +
            "-.946 0-1.739-.657-1.947-1.539v.002c-1.147.162-2.032 1.15-2.032 2.341v.007c1.776.067 3.4.567 4.686" +
            " 1.363.473-.363 1.064-.58 1.707-.58 1.547 0 2.802 1.254 2.802 2.802 0 1.117-.655 2.081-1.601 2.531" +
            "-.088 3.256-3.637 5.876-7.997 5.876-4.361 0-7.905-2.617-7.998-5.87-.954-.447-1.614-1.415-1.614-2.538 0" +
            "-1.548 1.255-2.802 2.803-2.802.645 0 1.239.218 1.712.585 1.275-.79 2.881-1.291 4.64-1.365v-.01c0-1.663" +
            " 1.263-3.034 2.88-3.207.188-.911.993-1.595 1.959-1.595Zm-8.085 8.376c-.784 0-1.459.78-1.506 1.797-.047" +
            " 1.016.64 1.429 1.426 1.429.786 0 1.371-.369 1.418-1.385.047-1.017-.553-1.841-1.338-1.841Zm7.406 0c" +
            "-.786 0-1.385.824-1.338 1.841.047 1.017.634 1.385 1.418 1.385.785 0 1.473-.413 1.426-1.429-.046-1.017" +
            "-.721-1.797-1.506-1.797Zm-3.703 4.013c-.974 0-1.907.048-2.77.135-.147.015-.241.168-.183.305.483 1.154" +
            " 1.622 1.964 2.953 1.964 1.33 0 2.47-.81 2.953-1.964.057-.137-.037-.29-.184-.305-.863-.087-1.795-.135" +
            "-2.769-.135Z",
        )
    }
  }

  private def brandMark(fill: String, path: String): SvgElement = {
    svg.svg(
      svg.cls     := "size-4",
      svg.viewBox := "0 0 24 24",
      svg.path(svg.fill := fill, svg.d := path),
    )
  }
}
