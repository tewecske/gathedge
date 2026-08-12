package gathedge.frontend.pages

import com.raquo.laminar.api.L._
import gathedge.frontend.api.{AdminApiClient, ApiError}
import gathedge.frontend.components.{AdminSubmenu, Alert, AppShell, FormField, Formats, Pagination, SortHeader}
import gathedge.frontend.listing.UserQuery
import gathedge.frontend.{AppRouter, Page}
import gathedge.shared.domain.User
import gathedge.shared.dto.{CreateUserRequest, RateLimitEntry, UserPage, UserSort}
import gathedge.frontend.i18n.I18n
import gathedge.shared.i18n.{MessageKeys, UiKeys}
import gathedge.shared.validation.Validation

object AdminUsersPage {

  /** The listing state arrives from the URL and is written back to it, so this page holds none of it: `query` is what
    * the address bar says, and `onQuery` is how to ask for a different address. See `App.renderers`, which keeps one
    * mounted page across every change to the query.
    */
  def render(query: Signal[UserQuery], onQuery: Observer[UserQuery]): HtmlElement = {
    AppShell.render(Page.Admin(), new AdminUsersPage(query, onQuery).render())
  }
}

/** All of the create-user form's state, including its validity. Field errors are derived rather than stored, so they
  * cannot go stale; `showErrors` keeps them hidden until the first submit attempt.
  */
private case class CreateUserForm(
  email: String = "",
  password: String = "",
  isAdmin: Boolean = false,
  showErrors: Boolean = false,
) {
  def emailError: Option[String] = I18n.errorOf(Validation.validateEmail(email))

  def passwordError: Option[String] = I18n.errorOf(Validation.validatePassword(password))

  def displayError(error: CreateUserForm => Option[String]): Option[String] = {
    if (showErrors)
      error(this)
    else
      None
  }

  /** `Some` exactly when the form is valid, so it doubles as the validity check. */
  def toRequest: Option[CreateUserRequest] = {
    for {
      validEmail    <- Validation.validateEmail(email).toOption
      validPassword <- Validation.validatePassword(password).toOption
    } yield CreateUserRequest(validEmail, validPassword, isAdmin)
  }
}

private class AdminUsersPage(pageQuery: Signal[UserQuery], onQuery: Observer[UserQuery]) {

  /** `.distinct` because every reader here treats an emission as "ask the server again". */
  private val querySignal = pageQuery.distinct

  private val usersVar    = Var(List.empty[User])
  private val usersSignal = usersVar.signal

  /** How many accounts match the current search, across every page — the server counts it, and the page control draws
    * its buttons off it.
    */
  private val totalVar    = Var(0L)
  private val totalSignal = totalVar.signal

  /** Which addresses the rate limiter is currently holding failures against, so the list can flag a locked-out account
    * without the list endpoint having to know about the limiter.
    *
    * Loaded alongside the users and joined here rather than server-side: the lock lives in a process-local `Ref`, it
    * changes on a timescale of minutes, and keeping it out of `GET /api/admin/users` leaves that endpoint — and its
    * entry in the OpenAPI document — exactly as it was. It is also why the sign-in column is the one column that cannot
    * be sorted: there is no `ORDER BY` that could produce it.
    */
  private val lockedVar    = Var(Set.empty[String])
  private val lockedSignal = lockedVar.signal

  private val sortSignal     = querySignal.map(_.sort).distinct
  private val pageSignal     = querySignal.map(_.page).distinct
  private val pageSizeSignal = querySignal.map(_.pageSize).distinct

  /** Every way this page asks for a different listing, as one stream of edits rather than a `Var` it owns.
    *
    * The state lives in the URL, so a writer cannot read the current value out of a local `Var` to change one field of
    * it. It emits the change instead, and [[render]] applies it to whatever the address bar currently says.
    */
  private val changeBus = new EventBus[UserQuery => UserQuery]()

  private def change(edit: UserQuery => UserQuery): Unit = changeBus.emit(edit)

  /** What the box shows. It follows the query in one direction only: seeded from it, and re-seeded whenever the term
    * changes, which is what puts `?q=bob` from a bookmark into the box and what restores it when the back button moves
    * through the history.
    *
    * Nothing reads it back. What the reader *types* travels on [[searchTypedBus]] instead, and the reason is that this
    * `Var` is written to through an `Observer`: a `Var` write opens a transaction, and a transaction opened inside a
    * running one is queued rather than applied. The page mounts inside one (the gate in `App` opens on a `Var` write of
    * its own), so at that instant this still holds `""` however the query is filtered — and a debounce built on its
    * signal would compare that `""` against `?q=bob` and "correct" the address by clearing the filter.
    */
  private val searchInputVar = Var("")

  /** What the reader typed, as a stream. A stream has no current value, so nothing at all is sent until somebody types
    * — which is the whole point: mounting the page can no longer look like an edit.
    */
  private val searchTypedBus = new EventBus[String]()

  private val formVar    = Var(CreateUserForm())
  private val formSignal = formVar.signal

  // Writable lenses into the one form Var, so inputs stay two-way bound without a Var per field.
  private val emailVar    = formVar.zoom(_.email)((form, email) => form.copy(email = email))
  private val passwordVar = formVar.zoom(_.password)((form, password) => form.copy(password = password))
  private val isAdminVar  = formVar.zoom(_.isAdmin)((form, isAdmin) => form.copy(isAdmin = isAdmin))

  private val emailErrorSignal    = formSignal.map(_.displayError(_.emailError))
  private val passwordErrorSignal = formSignal.map(_.displayError(_.passwordError))

  // Server-side failures only; field-level problems render next to their input.
  private val errorVar: Var[Option[String]] = Var(None)
  private val errorSignal                   = errorVar.signal

  /** Whether a *create* is in flight. It gates the submit, so it must not also mean "the table is loading": the two
    * requests are independent, and one flag for both means a page turn, a debounced search or a slow list query
    * silently drops the submit the administrator just made — the form would answer a click with nothing at all.
    */
  private val inFlightVar    = Var(false)
  private val inFlightSignal = inFlightVar.signal

  /** Whether the listing is being fetched. Only the paging control reads it, to grey its buttons out between pages. */
  private val loadingVar    = Var(false)
  private val loadingSignal = loadingVar.signal

  /** Asks for the current query again without changing it — after a create, and once on mount. */
  private val reloadBus = new EventBus[Unit]()
  private val createBus = new EventBus[Unit]()

  /** Every reason to call the list endpoint, as one stream: the query changed, or somebody asked for it again. One
    * stream rather than two subscriptions so a reload cancels an in-flight request instead of racing it.
    */
  private val listRequests = EventStream.merge(querySignal.updates, reloadBus.events.sample(querySignal))

  /** How long typing has to stop before the search is sent, in milliseconds — Airstream's `debounce` takes a plain
    * `Int`. Long enough that a typed word is one request, short enough that the list feels like it is following along.
    */
  private val searchDebounceMs = 300

  // Validation is pure; the effects hang off the resulting stream as observers.
  private val createStream = createBus.events.filterWith(inFlightSignal.not).map(_ => formVar.now().toRequest)

  def render(): HtmlElement = {
    div(
      h1(cls := "text-2xl font-bold mb-4", I18n.t(UiKeys.adminUsersTitle)),
      AdminSubmenu.render(Page.Admin()),
      Alert.maybeError(errorSignal),
      renderCreateForm(),
      renderSearch(),
      renderTable(),
      Pagination.render(
        page = pageSignal,
        total = totalSignal,
        pageSize = pageSizeSignal,
        onPage = Observer[Int](page => change(_.copy(page = page))),
        onPageSize = Observer[Int](size => change(_.reset(_.copy(pageSize = size)))),
        summary = totalSignal.map(summaryOf).distinct,
        busy = loadingSignal,
      ),
      // Each edit is applied to what the URL currently says, which is the only place the listing state lives.
      changeBus.events.withCurrentValueOf(querySignal).map { case (edit, current) => edit(current) } --> onQuery,
      querySignal.map(_.search).distinct --> searchInputVar.writer,
      // Only what was typed is debounced: the box itself stays immediate, and a page click or a column heading is not
      // affected by how recently anything was typed.
      //
      // The comparison keeps a term the URL already says from being asked for again — typing a letter and deleting it
      // is not a new search. It is no longer load-bearing for correctness; see `searchTypedBus` for what is.
      searchTypedBus.events.debounce(searchDebounceMs).withCurrentValueOf(querySignal) -->
        Observer[(String, UserQuery)] { case (typed, current) =>
          val wanted = typed.trim
          if (wanted != current.search) {
            change(_.reset(_.copy(search = wanted)))
          }
        },
      listRequests --> Observer[UserQuery](_ => Var.set(loadingVar -> true, errorVar -> None)),
      listRequests.flatMapSwitch(load) -->
        Observer[Either[ApiError, UserPage]] {
          case Right(result) =>
            Var.set(usersVar -> result.items, totalVar -> result.total, loadingVar -> false, errorVar -> None)
          case Left(err)     =>
            Var.set(loadingVar -> false, errorVar -> Some(err.message))
        },
      createStream -->
        Observer[Option[CreateUserRequest]] {
          case None    =>
            formVar.update(_.copy(showErrors = true))
          case Some(_) =>
            Var.set(inFlightVar -> true, errorVar -> None)
        },
      createStream
        .collect { case Some(request) =>
          request
        }
        .flatMapSwitch(request => AdminApiClient.createUser(request)) -->
        Observer[Either[ApiError, User]] {
          case Right(_)  =>
            // Back to the newest accounts, unfiltered: the account that was just created is then the first row,
            // whatever the administrator was looking at before. Nothing else can guarantee it is on screen — under a
            // search or a column sort, a new row lands wherever it lands. The search box empties itself, because it
            // follows the query.
            onQuery.onNext(UserQuery(sort = SortHeader.Sort.descending(UserSort.created)))
            Var.set(inFlightVar -> false, formVar -> CreateUserForm(), errorVar -> None)
            reloadBus.emit(())
          case Left(err) =>
            Var.set(inFlightVar -> false, errorVar -> Some(err.message))
        },
      // A failed lock lookup leaves the badges off rather than failing the page: the user list is the point, and the
      // lock state is an annotation on it.
      reloadBus.events.flatMapSwitch(_ => AdminApiClient.rateLimits) -->
        Observer[Either[ApiError, List[RateLimitEntry]]] {
          case Right(entries) =>
            lockedVar.set(lockedAddresses(entries))
          case Left(_)        =>
            lockedVar.set(Set.empty)
        },
      onMountCallback(_ => reloadBus.emit(())),
    )
  }

  private def load(query: UserQuery): EventStream[Either[ApiError, UserPage]] = {
    AdminApiClient.listUsers(
      page = Some(query.page),
      pageSize = Some(query.pageSize),
      sort = query.sort.column,
      dir = query.sort.wire,
      search = Option(query.search).filter(_.nonEmpty),
    )
  }

  /** What the paging control says the listing holds. Zero is worded as its own sentence rather than "0 accounts",
    * because with a search box above the table it is an answer rather than a count.
    */
  private def summaryOf(total: Long): String = {
    if (total <= 0L)
      I18n.t(UiKeys.adminUsersEmpty)
    else
      I18n.plural(UiKeys.adminUsersCount, total)
  }

  private def renderSearch(): HtmlElement = {
    div(
      cls := "flex flex-wrap items-end gap-2 mb-4",
      label(
        cls := "form-control",
        span(cls      := "label-text text-xs", I18n.t(UiKeys.adminUsersFilterLabel)),
        input(
          // `search` rather than `text`, so the browser offers its own clear button on the platforms that have one.
          cls         := "input input-sm",
          typ         := "search",
          placeholder := I18n.t(UiKeys.adminUsersFilterPlaceholder),
          controlled(value <-- searchInputVar.signal, onInput.mapToValue --> searchInputVar.writer),
          // The same keystroke, on its way to the query. Only an event of the reader's making reaches this, which is
          // what the write-back above must never be built on.
          onInput.mapToValue --> searchTypedBus.writer,
        ),
      ),
    )
  }

  private def renderCreateForm(): HtmlElement = {
    div(
      cls := "card bg-base-100 shadow mb-4",
      div(
        cls := "card-body",
        h2(cls       := "card-title", I18n.t(UiKeys.adminUsersCreateCard)),
        form(
          cls        := "flex flex-wrap gap-2 items-start",
          // Browser validation would pre-empt our own messages, and they differ from the server's rules.
          noValidate := true,
          onSubmit.preventDefault.mapToUnit --> createBus.writer,
          FormField.render(emailErrorSignal)(
            input(
              cls         := "input",
              cls("input-error") <-- emailErrorSignal.map(_.nonEmpty),
              typ         := "email",
              placeholder := I18n.t(MessageKeys.fieldEmail),
              controlled(value <-- emailVar.signal, onInput.mapToValue --> emailVar.writer),
            )
          ),
          FormField.render(passwordErrorSignal)(
            input(
              cls         := "input",
              cls("input-error") <-- passwordErrorSignal.map(_.nonEmpty),
              typ         := "password",
              placeholder := I18n.t(UiKeys.adminUsersPasswordPlaceholder, Validation.minPasswordLength),
              controlled(value <-- passwordVar.signal, onInput.mapToValue --> passwordVar.writer),
            )
          ),
          label(
            cls      := "label gap-2 h-12",
            input(
              typ := "checkbox",
              cls := "checkbox",
              controlled(checked <-- isAdminVar.signal, onClick.mapToChecked --> isAdminVar.writer),
            ),
            I18n.t(UiKeys.commonAdministrator),
          ),
          button(cls := "btn btn-primary", typ := "submit", disabled <-- inFlightSignal, I18n.t(UiKeys.commonCreate)),
        ),
      ),
    )
  }

  private def renderTable(): HtmlElement = {
    val onSort = Observer[SortHeader.Sort](sort => change(_.reset(_.copy(sort = sort))))

    div(
      cls := "overflow-x-auto card bg-base-100 shadow",
      table(
        cls := "table",
        thead(
          tr(
            SortHeader.render(I18n.t(MessageKeys.fieldEmail), UserSort.email, sortSignal, onSort),
            SortHeader.render(I18n.t(UiKeys.adminUsersColAdmin), UserSort.admin, sortSignal, onSort),
            SortHeader.render(I18n.t(UiKeys.adminUsersColConfirmed), UserSort.confirmed, sortSignal, onSort),
            // The one column with nothing behind it to order by; see `lockedVar`.
            th(I18n.t(UiKeys.adminUsersColSignIn)),
            SortHeader.render(I18n.t(UiKeys.adminUsersColCreated), UserSort.created, sortSignal, onSort),
          )
        ),
        tbody(
          children <--
            usersSignal.splitSeq(_.id) { userSignal =>
              renderRow(userSignal.key, userSignal)
            }
        ),
      ),
    )
  }

  private def renderRow(id: Long, userSignal: Signal[User]): HtmlElement = {
    tr(
      cls := "hover",
      // A link rather than a click handler on the row, so the detail page is reachable by keyboard.
      td(
        a(
          cls := "link link-hover",
          AppRouter.router.navigateTo(Page.AdminUserDetail(id)),
          // A guest has no address; the id is what identifies it on this screen instead.
          text <-- userSignal.map(user => user.email.getOrElse(s"#${user.id}")).distinct,
        )
      ),
      td(
        child <--
          userSignal
            .map(_.isAdmin)
            .distinct
            .map { isAdmin =>
              if (isAdmin)
                span(cls := "badge badge-primary", I18n.t(UiKeys.adminUsersBadgeAdmin))
              else
                span(cls := "badge badge-ghost", I18n.t(UiKeys.adminUsersBadgeUser))
            }
      ),
      td(
        child <--
          userSignal
            .map(_.emailVerified)
            .distinct
            .map { verified =>
              if (verified)
                span(cls := "badge badge-success badge-soft", I18n.t(UiKeys.adminUsersBadgeConfirmed))
              else
                span(cls := "badge badge-warning", I18n.t(UiKeys.adminUsersBadgeUnconfirmed))
            }
      ),
      td(
        child <--
          userSignal
            .map(_.email.getOrElse(""))
            .distinct
            .combineWith(lockedSignal)
            .map { (email, locked) =>
              // An empty address matches no key, so a guest is never shown as locked out — it has nothing to lock.
              if (email.nonEmpty && locked.contains(email.trim.toLowerCase))
                span(cls := "badge badge-error", I18n.t(UiKeys.adminUsersBadgeLocked))
              else
                span(cls := "badge badge-ghost", I18n.t(UiKeys.adminUsersBadgeOk))
            }
      ),
      td(text <-- userSignal.map(user => Formats.dateTimeFromString(user.createdAt)).distinct),
    )
  }

  /** The addresses out of the blocked keys, matching `RateLimitKey`'s `email:`/`verify:` namespaces. An `ip:` key is
    * skipped: it blocks an origin, not an account, so there is no row it belongs on.
    */
  private def lockedAddresses(entries: List[RateLimitEntry]): Set[String] = {
    entries
      .filter(_.blocked)
      .map(_.key)
      .flatMap { key =>
        List("email:", "verify:").find(key.startsWith).map(prefix => key.stripPrefix(prefix))
      }
      .toSet
  }
}
