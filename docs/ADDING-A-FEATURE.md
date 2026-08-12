# Adding a feature

The skeleton ships no example feature, so this is the map: every place a new resource has to touch,
in the order it makes sense to write them, and the reason each one exists.

Take `notes` as the running example — a resource owned by a user, listed and created through the
API, shown on a page.

> **There are complete worked examples in the git history.** The skeleton used to carry a to-do
> board and a group/invitation feature (roles, membership, emailed invites, a nested resource with
> path parameters). Both were removed so a new project starts clean, but commit `fd57e99` has them
> end to end. When something below is easier read than described — a generic repository, a service
> failure enum, a page that loads on mount — `git show fd57e99:<path>` is the fastest answer.

Read `CLAUDE.md` first if you have not. It states the rules; this file states the order.

---

## 1. The description, in `shared`

`modules/shared/shared/src/main/scala/<slug>/shared/api/NoteEndpoints.scala`

This is the only place the API is stated. Paths, request and response bodies, and **every status
code the endpoint can answer with** live here; the backend routes, the OpenAPI document and the
frontend client are all derived from it, so none of the three can drift.

```scala
object NoteEndpoints {
  val listNotes = {
    Endpoint(Method.GET / "api" / "notes").out[List[Note]].outFailure(failure.unauthorized)
  }

  val createNote = {
    Endpoint(Method.POST / "api" / "notes")
      .in[CreateNoteRequest]
      .withCodecError
      .out[Note](Status.Created)
      .outErrors(failure.badRequest, failure.unauthorized)
  }

  val all: List[Endpoint[?, ?, ?, ?, ?]] = List(listNotes, createNote)
}
```

Four things are easy to get wrong here, and each is a real bug:

- **Declare the 401** on anything the `authenticated` aspect guards. The aspect's 401 bypasses the
  endpoint's codecs, but a client generated from the description still has to *decode* it — and a
  status a description omits is not decodable at all. An expired session under an open page would
  reach the page as an unrenderable crash instead of a redirect.
- **Apply `.withCodecError` and declare a 400** whenever the endpoint has an input, a query
  parameter or a header codec — even if the handler cannot fail. A body that does not parse is
  caught by the library before the handler runs, and without the declaration the resulting 400
  matches neither the output nor the errors.
- **Do not declare 403, 429 or 500** for the CSRF aspect, the `adminOnly` aspect or a defect. Those
  are not answers to a well-formed request. Declare 403 only where your *service* raises a
  permission failure of its own.
- **For a 204, use `.outCodec(HttpCodec.status(Status.NoContent))`, not `.out[Unit]`.** The scaladoc
  on `AdminEndpoints.deleteUser` explains why: `out[Unit]` needs a `Content-Length: 0` a 204 must
  not send, and the browser client then fails to decode it.

`outErrors`' smallest overload takes two codecs; for a single one use `ApiEndpoint.outFailure`.

## 2. The DTOs and their schemas

`shared/domain/Note.scala` for the resource, `shared/dto/NoteDto.scala` for request bodies. Both
`derives JsonCodec`.

Then add a zio-schema instance for each in `shared/api/ApiSchemas.scala`. That is a *second* codec
stack — the `Endpoint` API needs a `Schema`, the DTOs also derive zio-json codecs — and nothing in
the type system makes them agree. `ApiEndpointsSpec` asserts the agreement on real bytes; add a case
there if your type has an enum in it. Declaration order matters: enums before the types embedding
them.

## 3. The migration, in both dialects

`modules/backend/src/main/resources/db/migration/{postgresql,sqlite}/V2__notes.sql`

Two files, kept schema-identical. Postgres is the only real target; SQLite exists so `sbt test`
needs no Docker. Conventions that are not optional:

- **Every timestamp is epoch millis** in a `BIGINT` (`INTEGER` on SQLite), never a native timestamp
  type — that is the one type the dialects genuinely disagree about.
- **A user reference cascades**, unless the row is a record of something that happened, in which case
  it is `ON DELETE SET NULL` (see `login_attempts` and `audit_log` in `V1`).
- SQLite cannot drop a `UNIQUE` column or alter a constraint; both need a table rebuild. Check what
  a change costs on that side before writing it on the Postgres side.

**Nothing enables `PRAGMA foreign_keys` on SQLite, so no constraint you write is enforced there.**
Referential integrity is exercised only by `PostgresIntegrationSpec` under `RUN_POSTGRES_TESTS=1`,
and that is where a regression test for a cascade belongs. Add your table's user reference to its
delete-user test — an account that cannot be deleted because of your foreign key answers 500, and
the whole SQLite suite passes regardless.

## 4. The repository

`modules/backend/src/main/scala/<slug>/backend/db/NoteRepository.scala` — the trait, the generic
implementation and both `ZLayer`s, all in one file.

```scala
trait NoteRepository { def listForUser(userId: Long): Task[List[NoteRow]] /* … */ }

object NoteRepository {
  def listForUser(userId: Long): RIO[NoteRepository, List[NoteRow]] =
    ZIO.serviceWithZIO[NoteRepository](_.listForUser(userId))

  val live: ZLayer[DataSource, Nothing, NoteRepository] = /* PostgresZioJdbcContext */
  val test: ZLayer[DataSource, Nothing, NoteRepository] = /* SqliteZioJdbcContext  */
}

final class NoteRepositoryLive[Dialect <: SqlIdiom, Naming <: NamingStrategy](
  dataSource: DataSource,
  quillContext: ZioJdbcContext[Dialect, Naming],
) extends QuillRepository(dataSource, quillContext) with NoteRepository { /* quoted queries */ }
```

The dialect is a type parameter so the queries are written once. The SQLite layer is called `test`
because nothing but tests may wire it. Add your row type to `db/Rows.scala`.

Every method logs one INFO line through `QuillRepository.logged`. **That message must never carry a
password hash, a session id, any opaque token, an OAuth subject or an email address** — log the
surrogate id or a `found=` flag.

## 5. The service

`backend/service/NoteService.scala`, with its own failure enum:

```scala
enum NoteFailure { case NotFound; case ValidationError(fieldErrors: Map[String, MessageRef]) }
```

Validation goes through `shared/validation/Validation.scala`, which runs on both platforms — so the
form and the endpoint behind it produce identical messages by construction. `validateNonBlank` takes
the field label's **catalog key**, not the label.

Anything that is a bug rather than a caller error gets `.orDie`; `RouteSupport.handleFailures` turns
a defect into a logged cause and a generic 500.

## 6. The failure mapping

One `NoteFailure -> ApiFailure` mapping, in `backend/http/ApiFailures.scala`, next to the others:

```scala
def note(failure: NoteFailure): ApiFailure.BadRequest | ApiFailure.NotFound = ...
```

Return the **union of the cases it can produce**, never bare `ApiFailure`. That union is what ties
the mapping to the description: a handler mapping through it only compiles if every status in the
union is one its endpoint declares.

One mapping per enum, here — not a private helper in the route file. Two route files that grew
separate mappings over the same enum is how the same failure came to answer with two different
statuses.

**This is where a message key is chosen, and no signature in that file takes a locale.** The server
puts a `MessageRef` on the wire and the browser words it; the English text alongside is a fallback
for callers with no catalog.

## 7. The routes

`backend/http/NoteRoutes.scala`:

```scala
val routes: Routes[NoteService & AuthService, Response] = {
  Routes(
    NoteEndpoints.listNotes.implementHandler(
      handler((_: Unit) => withContext((user: User) => NoteService.listNotes(user.id)))
    ),
    // …
  ) @@ RouteSupport.authenticated @@ RouteSupport.csrf
}
```

A handler is a plain function from the endpoint's input type to its output type — no path pattern,
no status, no JSON reading or writing. The cross-cutting checks are `HandlerAspect`s applied to the
whole `Routes` value, and they run last-attached-first, so the above checks CSRF before touching the
session.

**Never attach a context-providing aspect directly to a `handler` that also takes path parameters.**
It hands the handler a bare `Request` where it expects a `(param, Request)` tuple — that compiles
and then throws `ClassCastException` at request time. Put it on the `Routes` value.

If the resource mixes public and protected endpoints, build two `Routes` values and `++` them.

## 8. Wiring it up

In `backend/Main.scala`: add `NoteRoutes.routes` to the `++` chain, and `NoteRepository.live` and
`NoteService.live` to the layer list.

In `backend/http/DocsRoutes.scala`: add `NoteEndpoints.all` to `allEndpoints`. If any of your
endpoints is reachable without a session, add it to `publicEndpoints` too — that list is
hand-maintained because authentication is an aspect and nothing can read it back off a description.
`OpenApiSpec` pins both sides.

If the feature deserves a row on the administrator's system overview, add a `querySchema` and a
field to `TableCounts` in `db/MetricsRepository.scala`, a field to `dto.DbStats`, and a row in
`AdminSystemPage`. Keep it a count: a table may hold data no administrator is allowed to read.

## 9. The frontend client

One named method per endpoint in `frontend/api/ApiClient.scala`:

```scala
def listNotes: EventStream[Either[ApiError, List[Note]]] = run(executor(NoteEndpoints.listNotes(())))
```

No path strings, no method names. A failure is always a value on the success path, so a load driven
from `onMountCallback` never hangs on a rejected promise.

## 10. The page and the route

- A `Page` case in `frontend/AppRouter.scala`, plus its `Route`, plus its `serialize`/`deserialize`
  tag. `Page.guardFor` decides whether it needs a session.
- An arm in `App.renderPage`.
- The page itself in `frontend/pages/NotesPage.scala`, rendered through `AppShell.render(active,
  content)`.
- A nav entry in `AppShell` — remember the nav is **built twice** (a hamburger popover below `lg`, a
  row of buttons at `lg` and up) and each rendering is a separate element, because a Laminar element
  belongs to one place in the DOM.

If the page is a long listing, follow `AdminUsersPage`: its whole query state (page, size, sort,
direction, filters) is a case class carried *in the route*, so a filtered view can be bookmarked and
walked back through. `listing/ListingParams` has the four parameters every listing shares. Read the
"Paged, sorted and filtered listings" section of `CLAUDE.md` before writing one — five separate
things there are bugs if you get them wrong, and most of them were bugs once.

## 11. The copy

Every string a page renders is a constant in `shared/i18n/UiKeys.scala`, namespaced `ui.`, plus an
entry in **both** `web/public/locales/messages.{en,hu}.json`. Every message the *server* mints is a
constant in `MessageKeys.scala`.

- **Do not duplicate a field label.** If it already exists as a `MessageKeys` constant
  (`field.email`, `field.password`), the form renders that one.
- **Translate labels, never values.** In a `<select>`, the `option`'s `value` stays the enum's
  `toString` or the stored code; only its label goes through a key. `components/Labels.scala` is
  where an enum gets worded.
- **Never pass a bare string to `I18n.t`.** It compiles, and neither of `MessagesSpec`'s checks can
  see it — that exact hole is how `nav.language` sat outside the `ui.` namespace until an e2e run
  caught it.
- **Hungarian breaks two English habits.** `"3 item(s)"` has no equivalent — a numeral is followed by
  the singular, so use `MessageCatalog.plural` with a `.one`/`.other` pair. And the definite article
  alternates `a`/`az` by sound, which no placeholder can carry: phrase the frame to sidestep an
  interpolated noun, or keep the whole sentence as one key.

`MessagesSpec` fails until every constant exists in both catalogs, and fails again if a catalog
holds a `ui.` key nothing registers.

## 12. The tests

| What | Where |
|---|---|
| Service behaviour, against SQLite | `backend/src/test/.../service/NoteServiceSpec.scala` |
| Wire encoding — statuses, enums as bare strings, empty 204s | a suite in `ApiEndpointsSpec` |
| The OpenAPI status table | `OpenApiSpec` — it pins every operation, so it will fail until you add yours |
| Cascades and constraints | `PostgresIntegrationSpec`, and nowhere else |
| The page | `frontend/src/test/.../pages/NotesPageSpec.scala`, under jsdom |
| The whole path in a browser | `e2e/tests/golden-path.spec.ts` |

Frontend specs assert on message **keys**, not copy: with no catalog loaded under jsdom a message
resolves to its own key, which is the stronger statement — it says the page routed the right message
to the right place, and `MessagesSpec` separately proves that key has copy behind it.

The e2e suite is the exception: it runs under `/en/` and finds elements by their English text, so
changing a string in `messages.en.json` changes that suite's fixtures.

## Checklist

```
[ ] shared/api/NoteEndpoints.scala        — with 401s, .withCodecError, and no aspect statuses
[ ] shared/domain + shared/dto            — case classes, derives JsonCodec
[ ] shared/api/ApiSchemas.scala           — a Schema per type, enums first
[ ] db/migration/{postgresql,sqlite}      — two files, schema-identical, epoch-millis timestamps
[ ] backend/db/Rows.scala + NoteRepository.scala
[ ] backend/service/NoteService.scala     — with its failure enum
[ ] backend/http/ApiFailures.scala        — one mapping, returning a union
[ ] backend/http/NoteRoutes.scala         — aspects on the Routes value
[ ] backend/Main.scala                    — routes ++, layers
[ ] backend/http/DocsRoutes.scala         — allEndpoints, and publicEndpoints if applicable
[ ] frontend/api/ApiClient.scala          — one method per endpoint
[ ] frontend/AppRouter.scala + App.scala + pages/NotesPage.scala + AppShell nav
[ ] shared/i18n/UiKeys.scala + both message catalogs
[ ] tests: service, ApiEndpointsSpec, OpenApiSpec, PostgresIntegrationSpec, page, e2e
[ ] sbt scalafmtAll && sbt test
```
