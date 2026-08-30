package gathedge.backend.telemetry

import zio.*
import zio.telemetry.opentelemetry.OpenTelemetry
import zio.telemetry.opentelemetry.tracing.Tracing

/** The one OpenTelemetry wiring point.
  *
  * Per-SQL-statement spans, HikariCP pool metrics and JVM metrics are not built here — they come from the OpenTelemetry
  * Java agent attached to the runtime image (`-javaagent`, see the `Dockerfile` and `docker-compose.yml`). This layer
  * only adds the one span the agent cannot name well for a Netty-based server: the HTTP server span
  * (`RouteSupport.traced`), which becomes the parent of the agent's SQL spans.
  *
  *   - `OpenTelemetry.global` reads the SDK the agent registered. With no agent — `sbt test`, `npm run dev`, a plain
  *     `java -jar` — `GlobalOpenTelemetry.get()` returns a no-op, so every span call is a cheap no-op and nothing has
  *     to be conditionalised.
  *   - `OpenTelemetry.contextJVM` backs `Tracing`'s context with OpenTelemetry's own `ThreadLocal` `Context` rather
  *     than a `FiberRef`. That is what lets a span opened here and a span the agent opens inside a JDBC call share one
  *     parent — the interop path the agent has supported since 1.25.0.
  */
object Telemetry {

  val instrumentationScopeName = "gathedge-backend"

  val live: TaskLayer[Tracing] = {
    (OpenTelemetry.global ++ OpenTelemetry.contextJVM) >>> OpenTelemetry.tracing(instrumentationScopeName)
  }
}
