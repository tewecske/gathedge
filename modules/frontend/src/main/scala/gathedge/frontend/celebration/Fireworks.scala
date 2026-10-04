package gathedge.frontend.celebration

import gathedge.frontend.facades.Confetti

import scala.scalajs.js
import scala.scalajs.js.timers.{SetIntervalHandle, clearInterval, setInterval}

/** The fireworks the achievement dialog fires, from the "Fireworks" recipe of the `canvas-confetti` README: random
  * bursts from the two sides of the page, smaller as the time runs out.
  *
  * Only `App` refers to this object. The dialog gets [[launch]] as a plain function (see
  * `AchievementUnlockDialog.Fireworks`), so the `canvas-confetti` import stays out of every spec's graph. The specs
  * link with `NoModule` (see `build.sbt`), where an import does not link — the same reason `ImageOcr` gives.
  */
object Fireworks {

  private val durationMs = 3000.0
  private val intervalMs = 250.0

  /** Above the daisyUI `modal` (z-index 999), so the bursts draw over the dialog and its backdrop. */
  private val zIndex = 2000

  /** Starts the fireworks and gives back the function that stops them. Stopping twice is safe. A reader who asks for
    * less motion gets no fireworks: the package then fires nothing (`disableForReducedMotion`).
    */
  def launch(): () => Unit = {
    val end                                 = js.Date.now() + durationMs
    var interval: Option[SetIntervalHandle] = None
    def finish(): Unit                      = {
      interval.foreach(clearInterval)
      interval = None
    }
    interval = Some(setInterval(intervalMs) {
      val left = end - js.Date.now()
      if (left <= 0) {
        // The last bursts fall to the end on their own.
        finish()
      } else {
        val particles = (50 * left / durationMs).toInt
        burst(particles, randomIn(0.1, 0.3))
        burst(particles, randomIn(0.7, 0.9))
      }
    })

    // A stop from the dialog clears what is still on screen too.
    () => {
      finish()
      Confetti.reset()
    }
  }

  private def burst(particles: Int, x: Double): Unit = {
    Confetti(
      js.Dynamic.literal(
        particleCount = particles,
        startVelocity = 30,
        spread = 360,
        ticks = 60,
        zIndex = zIndex,
        disableForReducedMotion = true,
        origin = js.Dynamic.literal(x = x, y = math.random() - 0.2),
      )
    )
  }

  private def randomIn(min: Double, max: Double): Double = math.random() * (max - min) + min
}
