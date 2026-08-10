package webapp1.frontend.api

import com.raquo.laminar.api.L._
import webapp1.shared.api.{AuthEndpoints, GroupEndpoints, InvitationEndpoints, TodoEndpoints}
import webapp1.frontend.i18n.CurrentLocale
import webapp1.shared.domain.{Group, GroupMember, GroupPair, InvitationInfo, Locale, OAuthProvider, Theme, TodoItem}
import webapp1.shared.domain.Locale.code
import webapp1.shared.dto.{
  AuthResponse,
  CreateGroupRequest,
  CreatePairRequest,
  CreateTodoRequest,
  IdentitiesResponse,
  InviteMemberRequest,
  LoginRequest,
  ProvidersResponse,
  ResendVerificationRequest,
  SetPasswordRequest,
  SignupRequest,
  SignupResponse,
  UpdateLocaleRequest,
  UpdateRoleRequest,
  UpdateThemeRequest,
  UpdateTodoStatusRequest,
  VerifyEmailRequest,
}

import EndpointClient.{executor, run}
import OAuthProvider.wire

/** Every non-admin API call the pages make, generated from the endpoint descriptions in `shared` rather than written by
  * hand — see [[EndpointClient]]. There are no path strings or method names in this file; the admin resource is the
  * same, through [[AdminApiClient]].
  *
  * The signature is uniformly `EventStream[Either[ApiError, A]]`: callers `flatMapSwitch` these from a click or submit
  * stream and branch on the `Either`.
  */
object ApiClient {

  // --- Sessions -----------------------------------------------------------------------------------------------

  /** The session cookie the server sets comes back as the second half of the response, and is always dropped here: a
    * browser applies `Set-Cookie` itself and then hides it from `fetch`, so this is `None` in the page no matter what
    * the server sent (`ApiEndpoint.sessionCookie` explains why it is described as optional at all). The cookie is in
    * the jar regardless — that is what the next call authenticates with.
    */
  def signup(request: SignupRequest): EventStream[Either[ApiError, SignupResponse]] = {
    run(executor(AuthEndpoints.signup(request))).map(_.map(_._1))
  }

  /** Redeems the token out of a verification link. Public — the account it verifies typically cannot sign in yet. */
  def verifyEmail(token: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.verifyEmail(VerifyEmailRequest(token)))).map(_.map(_ => ()))
  }

  /** Answers the same whether or not the address has an account, so a page can only ever report "sent". */
  def resendVerification(email: String): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.resendVerification(ResendVerificationRequest(email)))).map(_.map(_ => ()))
  }

  def login(request: LoginRequest): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.login(request))).map(_.map(_._1))
  }

  def logout: EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.logout(()))).map(_.map(_ => ()))
  }

  def me: EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.me(())))
  }

  def updateTheme(theme: Theme): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.updateTheme(UpdateThemeRequest(theme))))
  }

  /** Records the choice; it does not change the current page's language. The picker navigates to the other prefix,
    * which is what actually switches languages — see `CurrentLocale`.
    */
  def updateLocale(locale: Locale): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.updateLocale(UpdateLocaleRequest(locale))))
  }

  // --- Account settings ---------------------------------------------------------------------------------------

  /** Public, and read by the sign-in and sign-up forms before any session exists — which is why it is separate from
    * [[identities]] rather than a field on it.
    */
  def providers: EventStream[Either[ApiError, ProvidersResponse]] = {
    run(executor(AuthEndpoints.providers(())))
  }

  def identities: EventStream[Either[ApiError, IdentitiesResponse]] = {
    run(executor(AuthEndpoints.identities(())))
  }

  def unlinkIdentity(provider: OAuthProvider): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.unlinkIdentity(provider.wire)))
  }

  def setPassword(request: SetPasswordRequest): EventStream[Either[ApiError, Unit]] = {
    run(executor(AuthEndpoints.setPassword(request)))
  }

  /** Where the browser must be *navigated* to start a social sign-in — deliberately a URL rather than a call.
    *
    * Everything else in this file is a `fetch` through the endpoint executor. These two cannot be: the flow is a chain
    * of top-level redirects through the provider and back, so it has to be the document that navigates, not an XHR.
    * That also puts them outside the generated client entirely, which is why this is the one place in the frontend that
    * spells out an API path.
    */
  def oauthStartUrl(provider: OAuthProvider, link: Boolean = false): String = {
    // `locale` rides in the query string because this URL is followed by the *document* navigating,
    // not by the generated client, so it carries none of the client's headers — `X-Locale` included.
    // The server tucks it into the `oauth_state` cookie so it survives the trip through the provider
    // and the callback knows which language's page to redirect back into.
    val params = {
      List(Option.when(link)("link=1"), Some(s"locale=${CurrentLocale.value.code}")).flatten
    }
    s"/api/auth/${provider.wire}/start?${params.mkString("&")}"
  }

  // --- Todos --------------------------------------------------------------------------------------------------

  def listTodos: EventStream[Either[ApiError, List[TodoItem]]] = {
    run(executor(TodoEndpoints.listTodos(())))
  }

  def createTodo(text: String): EventStream[Either[ApiError, TodoItem]] = {
    run(executor(TodoEndpoints.createTodo(CreateTodoRequest(text))))
  }

  def updateTodoStatus(id: Long, request: UpdateTodoStatusRequest): EventStream[Either[ApiError, TodoItem]] = {
    run(executor(TodoEndpoints.updateTodoStatus(id, request)))
  }

  // --- Groups -------------------------------------------------------------------------------------------------

  def createGroup(request: CreateGroupRequest): EventStream[Either[ApiError, Group]] = {
    run(executor(GroupEndpoints.createGroup(request)))
  }

  def listGroups: EventStream[Either[ApiError, List[Group]]] = {
    run(executor(GroupEndpoints.listGroups(())))
  }

  def getGroup(groupId: Long): EventStream[Either[ApiError, Group]] = {
    run(executor(GroupEndpoints.getGroup(groupId)))
  }

  def deleteGroup(groupId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.deleteGroup(groupId)))
  }

  def listPairs(groupId: Long): EventStream[Either[ApiError, List[GroupPair]]] = {
    run(executor(GroupEndpoints.listPairs(groupId)))
  }

  def addPair(groupId: Long, request: CreatePairRequest): EventStream[Either[ApiError, GroupPair]] = {
    run(executor(GroupEndpoints.addPair(groupId, request)))
  }

  def listMembers(groupId: Long): EventStream[Either[ApiError, List[GroupMember]]] = {
    run(executor(GroupEndpoints.listMembers(groupId)))
  }

  def removeMember(groupId: Long, userId: Long): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.removeMember(groupId, userId)))
  }

  def updateMemberRole(groupId: Long, userId: Long, request: UpdateRoleRequest): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.updateMemberRole(groupId, userId, request)))
  }

  def inviteMember(groupId: Long, request: InviteMemberRequest): EventStream[Either[ApiError, Unit]] = {
    run(executor(GroupEndpoints.inviteMember(groupId, request)))
  }

  // --- Invitations --------------------------------------------------------------------------------------------

  def getInvitation(token: String): EventStream[Either[ApiError, InvitationInfo]] = {
    run(executor(InvitationEndpoints.getInvitation(token)))
  }

  def acceptInvitation(token: String): EventStream[Either[ApiError, Group]] = {
    run(executor(InvitationEndpoints.acceptInvitation(token)))
  }
}
