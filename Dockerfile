# syntax=docker/dockerfile:1.7
#
# Two runtime images out of one build:
#   --target backend  ZIO HTTP server (the /api routes)
#   --target web      nginx serving the Scala.js SPA and proxying /api to the backend
# docker-compose.yml builds both. The backend serves no static files by design, so the two
# always ship together behind nginx, which makes the SPA same-origin with the API.

# ---------------------------------------------------------------------------------------------
# Shared build environment: JDK + sbt (matching project/build.properties) and Node for Vite.
# ---------------------------------------------------------------------------------------------
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.11_10_1.12.14_3.8.4 AS base

# Node comes from the official image rather than a package manager: this base is Ubuntu 26.04,
# which NodeSource has no repository for, and it ships no xz to unpack nodejs.org's tarball.
COPY --from=node:22-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=node:22-bookworm-slim /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm

WORKDIR /src
COPY . .

# ---------------------------------------------------------------------------------------------
# Backend: sbt-native-packager's `stage` produces bin/backend + lib/.
# ---------------------------------------------------------------------------------------------
FROM base AS backend-build

# The target/ mounts keep sbt incremental across builds. They are cache mounts, not layers, so the
# result has to be copied out inside the same RUN — nothing under them survives into the next one.
RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.ivy2 \
    --mount=type=cache,target=/src/target \
    --mount=type=cache,target=/src/project/target \
    --mount=type=cache,target=/src/modules/backend/target \
    --mount=type=cache,target=/src/modules/shared/.jvm/target \
    sbt -batch -no-colors backend/stage \
    && cp -a modules/backend/target/universal/stage /opt/backend

# ---------------------------------------------------------------------------------------------
# Frontend: `vite build` shells out to `sbt frontend/fullLinkJSOutput` through
# @scala-js/vite-plugin-scalajs, so this needs the whole repo, not just web/. Tailwind's
# `@source "../modules/frontend/src"` scans the Scala sources too.
#
# Chained onto backend-build rather than onto base so the two sbt runs are sequential — they share
# the coursier and sbt cache mounts, which are not safe to write concurrently.
# ---------------------------------------------------------------------------------------------
FROM backend-build AS web-build

RUN --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.ivy2 \
    --mount=type=cache,target=/root/.npm \
    --mount=type=cache,target=/src/target \
    --mount=type=cache,target=/src/project/target \
    --mount=type=cache,target=/src/modules/frontend/target \
    --mount=type=cache,target=/src/modules/shared/.js/target \
    npm --prefix web ci \
    && npm --prefix web run build \
    && cp -a web/dist /opt/web

# ---------------------------------------------------------------------------------------------
# Runtime: backend
# ---------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS backend

RUN groupadd --system app && useradd --system --gid app --home-dir /app app

WORKDIR /app
COPY --from=backend-build /opt/backend/ /app/
COPY docker/logback.xml /app/logback.xml

USER app
EXPOSE 8080

# The launcher script forwards -D arguments to the JVM. JAVA_OPTS (e.g. -Xmx512m) is honoured too.
ENTRYPOINT ["/app/bin/backend", "-Dlogback.configurationFile=/app/logback.xml"]

# ---------------------------------------------------------------------------------------------
# Runtime: static SPA + reverse proxy
# ---------------------------------------------------------------------------------------------
FROM nginx:1.27-alpine AS web

COPY --from=web-build /opt/web/ /usr/share/nginx/html/
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
