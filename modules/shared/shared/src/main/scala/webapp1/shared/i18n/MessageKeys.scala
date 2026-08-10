package webapp1.shared.i18n

import scala.collection.mutable

/** Every catalog key the *server* can put on the wire, as a constant.
  *
  * The frontend's own copy lives in the sibling [[UiKeys]], under the `ui.` prefix and with the same guarantee; the
  * split is by who mints the message, not by who renders it. What both objects exist for is the direction the en/hu
  * key-set comparison cannot see: a key minted by `ApiFailures`, `RouteSupport` or
  * [[webapp1.shared.validation.Validation]] and then missing from the catalogs would degrade to the key itself showing
  * up in the UI, and nothing would notice. `MessagesSpec` walks [[all]] and fails if any key is absent from either
  * catalog, which is what makes the stringly-typed catalog safe to rely on.
  *
  * Keys are registered as they are declared rather than re-listed in [[all]] by hand, because a hand-maintained list is
  * exactly the thing that goes stale and takes the guarantee with it.
  */
object MessageKeys {

  private val registered = mutable.LinkedHashSet.empty[String]

  private def key(value: String): String = {
    registered += value
    value
  }

  /** Every registered key. Safe to read once this object has initialised, which — being a `lazy val` on an object whose
    * own initialiser never touches it — is any time a caller can reach it.
    */
  lazy val all: Set[String] = registered.toSet

  // -- Validation ------------------------------------------------------------------------------
  // The `required`/`maxLength` pair take the offending field's *label key* as `{0}`, passed as a
  // MessageRef.keyArg; see Validation.validateNonBlank.

  val validationFailed: String         = key("validation.failed")
  val emailRequired: String            = key("validation.email.required")
  val emailTooLong: String             = key("validation.email.tooLong")
  val emailInvalid: String             = key("validation.email.invalid")
  val passwordRequired: String         = key("validation.password.required")
  val passwordTooShort: String         = key("validation.password.tooShort")
  val passwordTooLong: String          = key("validation.password.tooLong")
  val fieldRequired: String            = key("validation.field.required")
  val fieldTooLong: String             = key("validation.field.tooLong")
  val currentPasswordRequired: String  = key("validation.currentPassword.required")
  val currentPasswordIncorrect: String = key("validation.currentPassword.incorrect")

  // -- Field labels ----------------------------------------------------------------------------
  // Substituted into the two messages above. Referenced from both modules, which is why the
  // frontend and backend can no longer disagree about what a field is called — they used to, with
  // GroupsPage saying "Group name" and GroupService saying "Name" for the same input.

  val fieldEmail: String     = key("field.email")
  val fieldPassword: String  = key("field.password")
  val fieldGroupName: String = key("field.groupName")
  val fieldTodoText: String  = key("field.todoText")
  val fieldSource: String    = key("field.source")
  val fieldTarget: String    = key("field.target")

  // -- Authentication --------------------------------------------------------------------------

  val invalidCredentials: String       = key("auth.invalidCredentials")
  val emailAlreadyRegistered: String   = key("auth.emailAlreadyRegistered")
  val rateLimited: String              = key("auth.rateLimited")
  val emailNotVerified: String         = key("auth.emailNotVerified")
  val verificationTokenInvalid: String = key("auth.verificationTokenInvalid")
  val verificationSendFailed: String   = key("auth.verificationSendFailed")
  val lastCredential: String           = key("auth.lastCredential")

  // -- Social sign-in --------------------------------------------------------------------------
  // `oauthFailed` takes the provider's reason as {0}; `oauthAccountExists` takes its display name.

  val oauthFailed: String          = key("oauth.failed")
  val oauthAccountExists: String   = key("oauth.accountExists")
  val oauthAlreadyLinked: String   = key("oauth.alreadyLinked")
  val oauthUnknownProvider: String = key("oauth.unknownProvider")
  val oauthUnavailable: String     = key("oauth.unavailable")

  // -- Todo ------------------------------------------------------------------------------------

  val todoNotFound: String = key("todo.notFound")

  // -- Groups and invitations ------------------------------------------------------------------

  val groupNotFound: String       = key("group.notFound")
  val groupNotMember: String      = key("group.notMember")
  val groupReadOnlyMember: String = key("group.readOnlyMember")
  val groupAdminOnly: String      = key("group.adminOnly")
  val groupLastAdmin: String      = key("group.lastAdmin")
  val invitationInvalid: String   = key("invitation.invalid")

  // -- Administration --------------------------------------------------------------------------

  val adminUserNotFound: String   = key("admin.userNotFound")
  val adminSelfDemote: String     = key("admin.selfDemote")
  val adminSelfDelete: String     = key("admin.selfDelete")
  val adminLastCredential: String = key("admin.lastCredential")

  // -- Responses built outside the endpoint codecs ----------------------------------------------
  // RouteSupport's aspects and the OAuth routes assemble `dto.ErrorResponse` by hand, and
  // ApiEndpoint.withCodecError turns an undecodable body into `malformedRequest`.

  val internalError: String     = key("error.internal")
  val notFound: String          = key("error.notFound")
  val malformedRequest: String  = key("error.malformedRequest")
  val missingCsrfHeader: String = key("error.missingCsrfHeader")
  val notAuthenticated: String  = key("error.notAuthenticated")
  val notAdministrator: String  = key("error.notAdministrator")
  val invalidRedirect: String   = key("error.invalidRedirect")

  /** Not a server answer at all: the request never got one. Minted by `EndpointClient`, not by any route. */
  val requestFailed: String = key("error.requestFailed")

  // -- Email -----------------------------------------------------------------------------------
  // Resolved by the backend rather than the browser: these are the only messages the server itself
  // renders, and the only reason it loads a catalog at all.

  val emailVerifySubject: String = key("email.verify.subject")
  val emailVerifyBody: String    = key("email.verify.body")
  val emailInviteSubject: String = key("email.invite.subject")
  val emailInviteBody: String    = key("email.invite.body")
}
