package gathedge.frontend.components

import zio.test._

/** The share pages [[ShareTarget]] builds URLs for.
  *
  * What is worth pinning here is the encoding and the one target that can be absent: a link with a query string of its
  * own has to survive being put inside somebody else's query string, and Messenger must build nothing at all without
  * the app id its dialog demands.
  */
object ShareTargetSpec extends ZIOSpecDefault {

  private val link  = "https://gathedge.example/en/games/abc?tag=1&n=2"
  private val title = "Weekend words"
  private val appId = "1234567890"

  private def urlFor(target: ShareTarget, messengerAppId: Option[String] = None): String = {
    ShareTarget.shareUrl(target, link, title, messengerAppId).getOrElse("")
  }

  def spec = {
    suite("ShareTarget")(
      test("every target but Messenger builds a URL without any deployment configuration") {
        val built = ShareTarget.all.map(target => target -> ShareTarget.shareUrl(target, link, title, None))
        assertTrue(
          built.filter(_._2.isEmpty).map(_._1) == List(ShareTarget.Messenger),
          built.flatMap(_._2).forall(url => url.startsWith("https://") || url.startsWith("mailto:")),
        )
      },
      test("the link is encoded, so its own query string reaches the share page whole") {
        val encoded = "https%3A%2F%2Fgathedge.example%2Fen%2Fgames%2Fabc%3Ftag%3D1%26n%3D2"
        assertTrue(
          urlFor(ShareTarget.Facebook) == s"https://www.facebook.com/sharer/sharer.php?u=$encoded",
          urlFor(ShareTarget.Telegram) == s"https://t.me/share/url?url=$encoded&text=Weekend%20words",
          urlFor(ShareTarget.X) == s"https://x.com/intent/post?url=$encoded&text=Weekend%20words",
          urlFor(ShareTarget.Reddit) == s"https://www.reddit.com/submit?url=$encoded&title=Weekend%20words",
        )
      },
      test("WhatsApp and mail take the title and the link as one block of text") {
        assertTrue(
          urlFor(ShareTarget.WhatsApp) ==
            "https://wa.me/?text=Weekend%20words%20https%3A%2F%2Fgathedge.example%2Fen%2Fgames%2Fabc%3Ftag%3D1%26n%3D2",
          urlFor(ShareTarget.Email) ==
            "mailto:?subject=Weekend%20words&body=Weekend%20words%20https%3A%2F%2Fgathedge.example%2Fen" +
            "%2Fgames%2Fabc%3Ftag%3D1%26n%3D2",
        )
      },
      test("a page whose title has not arrived yet sends the bare link, with no space in front of it") {
        assertTrue(
          ShareTarget
            .shareUrl(ShareTarget.WhatsApp, "https://x.test/a", "  ", None)
            .contains(
              "https://wa.me/?text=https%3A%2F%2Fx.test%2Fa"
            )
        )
      },
      test("Messenger builds its dialog only with an app id, and comes back to the shared page afterwards") {
        assertTrue(
          urlFor(ShareTarget.Messenger, Some(appId)) ==
            s"https://www.facebook.com/dialog/send?app_id=$appId" +
            "&link=https%3A%2F%2Fgathedge.example%2Fen%2Fgames%2Fabc%3Ftag%3D1%26n%3D2" +
            "&redirect_uri=https%3A%2F%2Fgathedge.example%2Fen%2Fgames%2Fabc%3Ftag%3D1%26n%3D2",
          ShareTarget.shareUrl(ShareTarget.Messenger, link, title, None).isEmpty,
        )
      },
      test("the row leaves Messenger out until the app id arrives") {
        assertTrue(
          !ShareTarget.available(None).contains(ShareTarget.Messenger),
          ShareTarget.available(Some(appId)) == ShareTarget.all,
          ShareTarget.available(None).size == ShareTarget.all.size - 1,
        )
      },
    )
  }
}
