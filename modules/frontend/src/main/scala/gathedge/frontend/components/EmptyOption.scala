package gathedge.frontend.components

import com.raquo.laminar.api.L._
import com.raquo.laminar.codecs.Codec

/** The "nothing chosen" entry of a `<select>`: an option whose value is `""`.
  *
  * Use this, not `option(value := "", label)`. Laminar's `value` prop skips a write equal to the DOM's current value,
  * and an option with no text yet already has the value `""`. The label then becomes the option's value, so a select
  * set to `""` finds no match and shows nothing selected. The attribute is always written.
  */
object EmptyOption {

  private val valueAttr = htmlAttr("value", Codec.stringAsIs)

  def apply(label: String): HtmlElement = option(valueAttr := "", label)
}
