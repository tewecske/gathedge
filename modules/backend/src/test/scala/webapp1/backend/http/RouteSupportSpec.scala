package webapp1.backend.http

import webapp1.backend.service.RateLimitKey
import webapp1.shared.domain.Locale
import zio.http.*
import zio.test.*

import RouteRunner.getWithQuery

import java.net.InetAddress

/** Which address a request is held against.
  *
  * This is the input to every rate-limit key that is not an email address, and both ways of getting it wrong are
  * security bugs rather than inaccuracies. Believing `X-Forwarded-For` with nothing in front of the server lets a
  * client pick its own identity and so its own budget. Ignoring it with a proxy in front collapses every request onto
  * the proxy's address — which is what shipped: behind the compose stack's nginx, five failed sign-ins from anyone
  * blocked sign-in, sign-up and verification resends for every account, for as long as failures kept arriving.
  *
  * `clientAddress` is a pure function precisely so each branch below can be stated as one.
  */
object RouteSupportSpec extends ZIOSpecDefault {

  private val peer = "203.0.113.7"

  private def request(forwardedFor: Option[String], socketPeer: Option[String] = Some(peer)): Request = {
    val base = Request.get("/api/auth/login").copy(remoteAddress = socketPeer.map(InetAddress.getByName))
    forwardedFor.fold(base)(value => base.addHeader("X-Forwarded-For", value))
  }

  def spec = suite("RouteSupport")(addressSuite, keySuite, logSuite, localeSuite)

  /** The request log used to write the whole URL, and two of this API's URLs carry a credential. Every case below is
    * one that reached `logs/backend.log` and `docker logs` on every request.
    */
  private val logSuite = {
    suite("loggableUrl")(
      test("an invitation token never reaches the log line") {
        val token = "0Fh3Kx9QpL7mN2sT4vW6yZ8bC1dE5gH7jK9lM0nO2pQ"
        assertTrue(
          RouteSupport.loggableUrl(Request.get(s"/api/invitations/$token")) == "/api/invitations/…",
          RouteSupport.loggableUrl(Request.post(s"/api/invitations/$token/accept", Body.empty)) ==
            "/api/invitations/…/accept",
          !RouteSupport.loggableUrl(Request.get(s"/api/invitations/$token")).contains(token),
        )
      },
      test("the OAuth authorization code goes with the query string") {
        val request = getWithQuery("/api/auth/google/callback?code=4%2F0AY0e-secret&state=abc123")
        val logged  = RouteSupport.loggableUrl(request)
        assertTrue(logged == "/api/auth/google/callback", !logged.contains("secret"), !logged.contains("abc123"))
      },
      test("an ordinary path is logged as it is") {
        assertTrue(
          RouteSupport.loggableUrl(Request.get("/api/me")) == "/api/me",
          RouteSupport.loggableUrl(Request.get("/api/admin/users/42")) == "/api/admin/users/42",
        )
      },
      test("a path too short to hold a token is left alone rather than indexed into") {
        assertTrue(RouteSupport.loggableUrl(Request.get("/api/invitations")) == "/api/invitations")
      },
    )
  }

  private val addressSuite = {
    suite("RouteSupport.clientAddress")(
      test("with no trusted proxy the header is ignored entirely") {
        // The whole point of the zero default: a client talking to us directly writes this header itself.
        assertTrue(
          RouteSupport.clientAddress(request(Some("198.51.100.4")), 0).contains(peer),
          RouteSupport.clientAddress(request(None), 0).contains(peer),
        )
      },
      test("one trusted proxy takes the entry that proxy appended") {
        // nginx sets `$proxy_add_x_forwarded_for`, so with no inbound header the only entry is the real client.
        assertTrue(RouteSupport.clientAddress(request(Some("198.51.100.4")), 1).contains("198.51.100.4"))
      },
      test("a client cannot move the answer by sending a header of its own") {
        // The spoofed value is pushed left of the one the proxy appends, and the rightmost entry still wins.
        val spoofed = request(Some("10.0.0.1, 198.51.100.4"))
        val piled   = request(Some("10.0.0.1, 10.0.0.2, 10.0.0.3, 198.51.100.4"))
        assertTrue(
          RouteSupport.clientAddress(spoofed, 1).contains("198.51.100.4"),
          RouteSupport.clientAddress(piled, 1).contains("198.51.100.4"),
        )
      },
      test("two trusted proxies read one entry further left") {
        // A CDN in front of nginx: the CDN appends the client, nginx appends the CDN.
        val forwarded = request(Some("198.51.100.4, 192.0.2.50"))
        assertTrue(
          RouteSupport.clientAddress(forwarded, 2).contains("198.51.100.4"),
          RouteSupport.clientAddress(forwarded, 1).contains("192.0.2.50"),
        )
      },
      test("a header shorter than the claimed hop count falls back to the socket peer") {
        // Someone reached the backend without going through the proxies it is configured for. The peer is the one
        // value that cannot be forged, so it is the safe answer — a shared budget, never a bypassed one.
        assertTrue(
          RouteSupport.clientAddress(request(Some("198.51.100.4")), 2).contains(peer),
          RouteSupport.clientAddress(request(None), 1).contains(peer),
          RouteSupport.clientAddress(request(Some("   ")), 1).contains(peer),
        )
      },
      test("an entry that is not address-shaped is discarded rather than used as a key") {
        assertTrue(
          RouteSupport.clientAddress(request(Some("not an address")), 1).contains(peer),
          RouteSupport.clientAddress(request(Some("<script>, 198.51.100.4")), 1).contains("198.51.100.4"),
        )
      },
      test("no socket peer and no usable header is no address at all") {
        // Both `RequestContext` fields are optional; the limiter simply drops the origin dimension.
        assertTrue(RouteSupport.clientAddress(request(None, socketPeer = None), 0).isEmpty)
      },
      test("IPv6 forwards through, and keys on its /64") {
        val forwarded = request(Some("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        assertTrue(RouteSupport.clientAddress(forwarded, 1).contains("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
      },
    )
  }

  private val keySuite = {
    suite("RateLimitKey.ip")(
      // A /64 is the smallest unit an IPv6 client cannot move within for free. Keying on the address would hand a
      // single customer as many budgets as they cared to bind.
      test("two addresses in one IPv6 /64 share a key") {
        assertTrue(
          RateLimitKey.ip("2001:db8:1:2::1") == RateLimitKey.ip("2001:db8:1:2::2"),
          RateLimitKey.ip("2001:db8:1:2::1") == RateLimitKey.ip("2001:db8:1:2:ffff:ffff:ffff:ffff"),
        )
      },
      test("a different /64 is a different key") {
        assertTrue(RateLimitKey.ip("2001:db8:1:2::1") != RateLimitKey.ip("2001:db8:1:3::1"))
      },
      test("IPv4 keys on the exact address") {
        // No aggregation here on purpose: a /24 would sweep unrelated customers behind one NAT into one budget.
        assertTrue(
          RateLimitKey.ip("198.51.100.4") != RateLimitKey.ip("198.51.100.5"),
          RateLimitKey.ip("198.51.100.4") == "ip:198.51.100.4",
        )
      },
      test("something that parses as neither is still a usable key") {
        assertTrue(RateLimitKey.ip("  unknown  ") == "ip:unknown")
      },
    )
  }

  /** Which language a request is being made in. `X-Locale` is authoritative because it carries the URL prefix the SPA
    * is actually running under; `Accept-Language` is only ever a hint, since a browser's language list says nothing
    * about which prefix the user chose to open.
    */
  private val localeSuite = {
    suite("localeOf")(
      test("X-Locale decides it") {
        val request = Request.get("/api/auth/signup").addHeader("X-Locale", "hu")
        assertTrue(RouteSupport.localeOf(request) == Locale.Hu)
      },
      test("X-Locale wins over Accept-Language") {
        val request = Request
          .get("/api/auth/signup")
          .addHeader("X-Locale", "en")
          .addHeader("Accept-Language", "hu-HU,hu;q=0.9")
        assertTrue(RouteSupport.localeOf(request) == Locale.En)
      },
      // For anything that is not this SPA: a curl, a future mobile client.
      test("Accept-Language is the fallback, region subtag and all") {
        val request = Request.get("/api/auth/signup").addHeader("Accept-Language", "hu-HU,hu;q=0.9,en;q=0.8")
        assertTrue(RouteSupport.localeOf(request) == Locale.Hu)
      },
      test("the first understood language wins, not the first listed") {
        val request = Request.get("/api/auth/signup").addHeader("Accept-Language", "de-DE,fr;q=0.9,hu;q=0.8")
        assertTrue(RouteSupport.localeOf(request) == Locale.Hu)
      },
      test("neither header, or one naming a language we do not have, falls back to the default") {
        assertTrue(
          RouteSupport.localeOf(Request.get("/api/auth/signup")) == Locale.default,
          RouteSupport.localeOf(Request.get("/api/auth/signup").addHeader("X-Locale", "kl")) == Locale.default,
          RouteSupport.localeOf(Request.get("/api/auth/signup").addHeader("Accept-Language", "de,fr")) == Locale.default,
        )
      },
    )
  }

}
