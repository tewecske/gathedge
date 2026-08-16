package gathedge.frontend.facades

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Thin facade over the `qrcode` npm package (pinned in `web/package.json`) — the only client-side dependency this app
  * has for turning a URL into a picture. Everything runs in the browser with no network round trip, so it works for a
  * shared game link before anyone has signed in.
  *
  * `Namespace` rather than `Default`: the package is plain CommonJS (`exports.toDataURL = …`, no `__esModule` flag),
  * and a namespace import is what survives both of Vite's two bundling paths (esbuild's CJS shim in dev, Rollup's
  * `@rollup/plugin-commonjs` in a production build) without depending on either one's named-export detection.
  *
  * Only [[toDataURL]] is declared, because it is the only call this app makes: a `data:` URI PNG, small enough to hand
  * straight to an `<img src>` with nothing else to wire up. The package also renders to SVG and `<canvas>`, unused
  * here.
  */
@js.native
@JSImport("qrcode", JSImport.Namespace)
object QRCode extends js.Object {

  /** Resolves to a `data:image/png;base64,…` URI encoding `text` as a QR code. Rejects only if `text` cannot be encoded
    * at all (never for a network reason — there is no network call).
    */
  def toDataURL(text: String): js.Promise[String] = js.native
}
