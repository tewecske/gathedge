---
name: metals-direct-lsp
description: Semantic Code Intelligence — metals-mcp (Direct HTTP)
---

**Scala** uses [metals-mcp](https://github.com/scalameta/metals/tree/main/metals-mcp) via direct HTTP calls (curl). **Do not register metals-mcp as a Claude Code MCP server** — Claude Code's MCP client adds ~7s overhead per call; calling metals-mcp directly via curl returns results in ~30ms.

### How to use metals-mcp

Start metals-mcp as HTTP server for target project, then query via curl:

```bash
# Start (once per session, runs in background)
metals-mcp --workspace /path/to/project --port <port> &

# Initialize MCP session
SESSION_ID=$(curl -s --dump-header - --output /dev/null http://localhost:<port>/mcp -X POST \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"agent","version":"1.0"}}}' | \
  awk 'BEGIN{IGNORECASE=1} $0 ~ /^mcp-session-id[[:space:]]*:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); sub(/\r$/, "", $0); print; exit}')

# Send initialized notification
curl -s http://localhost:<port>/mcp -X POST \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -H "mcp-session-id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null

# Query (e.g., get-usages)
curl -s http://localhost:<port>/mcp -X POST \
  -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
  -H "mcp-session-id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get-usages","arguments":{"fqcn":"com.example.MyClass.myField","module":"core"}}}'
```

Available tools: `get-usages`, `get-source`, `get-docs`, `inspect`, `glob-search`, `typed-glob-search`, `find-dep`, `compile-file`, `compile-full`, `compile-module`, `list-modules`, `test`, `format-file`, `list-scalafix-rules`, `run-scalafix-rule`, `generate-scalafix-rule`, `import-build`.

### Why avoid Claude Code MCP client/registration

Benchmarked metals-mcp through Claude Code's MCP client (both stdio and HTTP transport) vs direct HTTP. Claude Code adds **~7s overhead per call** regardless of transport. Direct HTTP returns same results in **~30ms**. ~230x slowdown makes Claude Code's MCP client unusable for iterative queries. Full benchmarks in [anthropics/claude-code#45132](https://github.com/anthropics/claude-code/issues/45132).

### Prerequisites

- **Coursier** (`cs`) — installs metals-mcp
- **metals-mcp** — installable via `cs install metals-mcp`

### Setup (per project)

- **metals-mcp** — standalone MCP server bundling Metals. Manages own Bloop/BSP connections. No separate Metals config needed.
- **Bloop** — metals-mcp auto-starts Bloop if not running. `sbt-bloop` must be added **per-project** in `project/plugins.sbt` (not globally) — different sbt versions need incompatible bloop versions.
- **SemanticDB** — enabled per-project in `build.sbt` (not globally — different Scala versions need different semanticdb versions)

### Known limitations

- **Scala case class field refs** need Bloop + SemanticDB + compiled project. Without all three → `get-usages` returns empty results.

### metals-mcp vs Grep Decision

metals-mcp (direct HTTP) returns results in ~30ms — same order of magnitude as grep (~13ms). Both fast enough for interactive use. Choose by **precision needs**, not speed:

- **Grep** when: searching non-code files, broad text patterns, or symbol is unique enough that false positives unlikely.
- **metals-mcp** when: symbol common across types (e.g., `id`, `deleted`, `name`, `status`), need type-aware refs without false positives, understanding call graphs, or refactoring impact analysis. Also when grep returns too many irrelevant hits.
