package gathedge.shared.api

import gathedge.shared.domain.{Gender, Locale, OAuthProvider, PartOfSpeech, Tag, Theme, User, Word, WordLanguage}
import gathedge.shared.dto.{
  AddTranslationRequest,
  AdminIdentityInfo,
  AdminSessionInfo,
  AdminUserDetail,
  AdminVerificationTokenInfo,
  AuditEntry,
  AuditPage,
  AuthResponse,
  ClaimCodeResponse,
  ClaimRequest,
  ClearRateLimitRequest,
  ConfigSummary,
  CreateTagRequest,
  CreateUserRequest,
  CreateWordRequest,
  DbStats,
  IdentitiesResponse,
  JobStatus,
  LinkedIdentity,
  LockoutStatus,
  LoginAttemptEntry,
  LoginRequest,
  MigrationInfo,
  NewTranslation,
  ProvidersResponse,
  PruneResult,
  RateLimitEntry,
  ResendVerificationRequest,
  RuntimeInfo,
  SetPasswordRequest,
  SignupRequest,
  SignupResponse,
  SystemOverview,
  TranslationEntry,
  UpdateLocaleRequest,
  UpdateThemeRequest,
  UpdateUserRequest,
  UpgradeRequest,
  UserPage,
  VerifyEmailRequest,
  WordDetail,
  WordPage,
  WordSummary,
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

  given Schema[Theme]         = DeriveSchema.gen[Theme]
  given Schema[Locale]        = DeriveSchema.gen[Locale]
  given Schema[OAuthProvider] = DeriveSchema.gen[OAuthProvider]
  given Schema[WordLanguage]  = DeriveSchema.gen[WordLanguage]
  given Schema[PartOfSpeech]  = DeriveSchema.gen[PartOfSpeech]
  given Schema[Gender]        = DeriveSchema.gen[Gender]

  given Schema[User] = DeriveSchema.gen[User]
  given Schema[Word] = DeriveSchema.gen[Word]
  given Schema[Tag]  = DeriveSchema.gen[Tag]

  given Schema[AuthResponse]              = DeriveSchema.gen[AuthResponse]
  given Schema[SignupResponse]            = DeriveSchema.gen[SignupResponse]
  given Schema[SignupRequest]             = DeriveSchema.gen[SignupRequest]
  given Schema[VerifyEmailRequest]        = DeriveSchema.gen[VerifyEmailRequest]
  given Schema[ResendVerificationRequest] = DeriveSchema.gen[ResendVerificationRequest]
  given Schema[LoginRequest]              = DeriveSchema.gen[LoginRequest]
  given Schema[UpdateThemeRequest]        = DeriveSchema.gen[UpdateThemeRequest]
  given Schema[UpdateLocaleRequest]       = DeriveSchema.gen[UpdateLocaleRequest]
  given Schema[LinkedIdentity]            = DeriveSchema.gen[LinkedIdentity]
  given Schema[IdentitiesResponse]        = DeriveSchema.gen[IdentitiesResponse]
  given Schema[SetPasswordRequest]        = DeriveSchema.gen[SetPasswordRequest]
  given Schema[ProvidersResponse]         = DeriveSchema.gen[ProvidersResponse]

  given Schema[ClaimCodeResponse] = DeriveSchema.gen[ClaimCodeResponse]
  given Schema[ClaimRequest]      = DeriveSchema.gen[ClaimRequest]
  given Schema[UpgradeRequest]    = DeriveSchema.gen[UpgradeRequest]

  given Schema[WordSummary]           = DeriveSchema.gen[WordSummary]
  given Schema[TranslationEntry]      = DeriveSchema.gen[TranslationEntry]
  given Schema[WordDetail]            = DeriveSchema.gen[WordDetail]
  given Schema[NewTranslation]        = DeriveSchema.gen[NewTranslation]
  given Schema[CreateWordRequest]     = DeriveSchema.gen[CreateWordRequest]
  given Schema[AddTranslationRequest] = DeriveSchema.gen[AddTranslationRequest]
  given Schema[CreateTagRequest]      = DeriveSchema.gen[CreateTagRequest]

  given Schema[CreateUserRequest] = DeriveSchema.gen[CreateUserRequest]
  given Schema[UpdateUserRequest] = DeriveSchema.gen[UpdateUserRequest]

  given Schema[AdminSessionInfo]           = DeriveSchema.gen[AdminSessionInfo]
  given Schema[AdminIdentityInfo]          = DeriveSchema.gen[AdminIdentityInfo]
  given Schema[AdminVerificationTokenInfo] = DeriveSchema.gen[AdminVerificationTokenInfo]
  given Schema[LockoutStatus]              = DeriveSchema.gen[LockoutStatus]
  given Schema[LoginAttemptEntry]          = DeriveSchema.gen[LoginAttemptEntry]
  given Schema[AdminUserDetail]            = DeriveSchema.gen[AdminUserDetail]
  given Schema[AuditEntry]                 = DeriveSchema.gen[AuditEntry]
  given Schema[RateLimitEntry]             = DeriveSchema.gen[RateLimitEntry]

  // The two paged listings. Concrete rather than one generic `Page[A]`, which would need a `given [A: Schema]` and buys
  // a shared field name in exchange for a derivation that only ever has two instantiations.
  given Schema[UserPage]  = DeriveSchema.gen[UserPage]
  given Schema[AuditPage] = DeriveSchema.gen[AuditPage]
  given Schema[WordPage]  = DeriveSchema.gen[WordPage]

  given Schema[ClearRateLimitRequest] = DeriveSchema.gen[ClearRateLimitRequest]

  given Schema[ConfigSummary]  = DeriveSchema.gen[ConfigSummary]
  given Schema[MigrationInfo]  = DeriveSchema.gen[MigrationInfo]
  given Schema[JobStatus]      = DeriveSchema.gen[JobStatus]
  given Schema[RuntimeInfo]    = DeriveSchema.gen[RuntimeInfo]
  given Schema[DbStats]        = DeriveSchema.gen[DbStats]
  given Schema[SystemOverview] = DeriveSchema.gen[SystemOverview]
  given Schema[PruneResult]    = DeriveSchema.gen[PruneResult]
}
