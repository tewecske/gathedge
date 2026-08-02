package webapp1.frontend.api

import com.raquo.laminar.api.L._
import webapp1.shared.api.{AuthEndpoints, GroupEndpoints, InvitationEndpoints, TodoEndpoints}
import webapp1.shared.domain.{Group, GroupMember, GroupPair, InvitationInfo, Theme, TodoItem}
import webapp1.shared.dto.{
  AuthResponse,
  CreateGroupRequest,
  CreatePairRequest,
  CreateTodoRequest,
  InviteMemberRequest,
  LoginRequest,
  SignupRequest,
  UpdateRoleRequest,
  UpdateThemeRequest,
  UpdateTodoStatusRequest,
}

import EndpointClient.{executor, run}

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
  def signup(request: SignupRequest): EventStream[Either[ApiError, AuthResponse]] = {
    run(executor(AuthEndpoints.signup(request))).map(_.map(_._1))
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
