# The two Java agents the server can run the backend with. `services.gathedge.telemetry.agent`
# picks one (nix/module.nix); the flake sets its default per release (flake.nix).
#
# Same jars and versions sbt resolves for `backend/reStart` (`otelAgentVersion`, `sentryVersion` in
# build.sbt). Keep them in step. A version bump needs a new hash:
#   nix-prefetch-url <url>   then   nix hash convert --hash-algo sha256 --to sri <hash>
{ fetchurl }:
{
  otel = fetchurl {
    url = "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.31.1/opentelemetry-javaagent-2.31.1.jar";
    hash = "sha256-u/g8FRtkAHCeLyJb3QegT4OdnRO4uTRkJBMz/SXT47o=";
  };

  # Sentry's build of the OpenTelemetry agent. It sends spans to Sentry, not over OTLP.
  sentry = fetchurl {
    url = "https://repo1.maven.org/maven2/io/sentry/sentry-opentelemetry-agent/8.60.0/sentry-opentelemetry-agent-8.60.0.jar";
    hash = "sha256-SFMha1+WUkO84MK5L8+dSEVfuBQj/CCknF4rA5GGmdA=";
  };
}
