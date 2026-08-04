package webapp1.shared.api

import webapp1.shared.domain.{
  Group,
  GroupMember,
  GroupPair,
  GroupRole,
  InvitationInfo,
  OAuthProvider,
  Theme,
  TodoItem,
  TodoStatus,
  User,
}
import webapp1.shared.dto.{
  AuthResponse,
  CreateGroupRequest,
  CreatePairRequest,
  CreateTodoRequest,
  CreateUserRequest,
  IdentitiesResponse,
  InviteMemberRequest,
  LinkedIdentity,
  LoginRequest,
  ProvidersResponse,
  SetPasswordRequest,
  SignupRequest,
  UpdateRoleRequest,
  UpdateThemeRequest,
  UpdateTodoStatusRequest,
  UpdateUserRequest,
}
import zio.schema.{DeriveSchema, Schema}

/** The zio-schema instances the endpoint descriptions are built from.
  *
  * This is the project's *second* codec stack: the DTOs and domain types already derive zio-json codecs, which is what
  * `dto`/`domain` carry and what every hand-written JSON body used before. The `Endpoint` API does not use those — it
  * needs a `Schema`, and derives both the wire codec and the OpenAPI schema from it. Keeping the instances here rather
  * than on the companions keeps that second stack contained to this package, and makes it obvious that the two have to
  * agree on the wire format. Nothing in the type system enforces the agreement; `ApiEndpointsSpec` asserts it on real
  * bytes, per type.
  *
  * Declaration order matters: a derived schema needs the schemas of its fields already in scope, so the enums and the
  * types they are embedded in come first.
  */
object ApiSchemas {

  given Schema[Theme] = DeriveSchema.gen[Theme]
  given Schema[TodoStatus] = DeriveSchema.gen[TodoStatus]
  given Schema[GroupRole] = DeriveSchema.gen[GroupRole]
  given Schema[OAuthProvider] = DeriveSchema.gen[OAuthProvider]

  given Schema[User] = DeriveSchema.gen[User]
  given Schema[TodoItem] = DeriveSchema.gen[TodoItem]
  given Schema[Group] = DeriveSchema.gen[Group]
  given Schema[GroupPair] = DeriveSchema.gen[GroupPair]
  given Schema[GroupMember] = DeriveSchema.gen[GroupMember]
  given Schema[InvitationInfo] = DeriveSchema.gen[InvitationInfo]

  given Schema[AuthResponse] = DeriveSchema.gen[AuthResponse]
  given Schema[SignupRequest] = DeriveSchema.gen[SignupRequest]
  given Schema[LoginRequest] = DeriveSchema.gen[LoginRequest]
  given Schema[UpdateThemeRequest] = DeriveSchema.gen[UpdateThemeRequest]
  given Schema[LinkedIdentity] = DeriveSchema.gen[LinkedIdentity]
  given Schema[IdentitiesResponse] = DeriveSchema.gen[IdentitiesResponse]
  given Schema[SetPasswordRequest] = DeriveSchema.gen[SetPasswordRequest]
  given Schema[ProvidersResponse] = DeriveSchema.gen[ProvidersResponse]

  given Schema[CreateTodoRequest] = DeriveSchema.gen[CreateTodoRequest]
  given Schema[UpdateTodoStatusRequest] = DeriveSchema.gen[UpdateTodoStatusRequest]

  given Schema[CreateGroupRequest] = DeriveSchema.gen[CreateGroupRequest]
  given Schema[CreatePairRequest] = DeriveSchema.gen[CreatePairRequest]
  given Schema[InviteMemberRequest] = DeriveSchema.gen[InviteMemberRequest]
  given Schema[UpdateRoleRequest] = DeriveSchema.gen[UpdateRoleRequest]

  given Schema[CreateUserRequest] = DeriveSchema.gen[CreateUserRequest]
  given Schema[UpdateUserRequest] = DeriveSchema.gen[UpdateUserRequest]
}
