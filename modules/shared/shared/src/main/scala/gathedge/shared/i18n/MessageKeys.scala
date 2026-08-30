package gathedge.shared.i18n

import scala.collection.mutable

/** Every catalog key the *server* can put on the wire, as a constant.
  *
  * The frontend's own copy lives in the sibling [[UiKeys]], under the `ui.` prefix and with the same guarantee; the
  * split is by who mints the message, not by who renders it. What both objects exist for is the direction the en/hu
  * key-set comparison cannot see: a key minted by `ApiFailures`, `RouteSupport` or
  * [[gathedge.shared.validation.Validation]] and then missing from the catalogs would degrade to the key itself showing
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
  // frontend and backend cannot disagree about what a field is called: a form input whose label
  // already exists here renders this key rather than minting a second one in `UiKeys`.

  val fieldEmail: String     = key("field.email")
  val fieldPassword: String  = key("field.password")
  val fieldWord: String      = key("field.word")
  val fieldTag: String       = key("field.tag")
  val fieldGameName: String  = key("field.gameName")
  val fieldGroupName: String = key("field.groupName")

  // -- Authentication --------------------------------------------------------------------------

  val invalidCredentials: String        = key("auth.invalidCredentials")
  val emailAlreadyRegistered: String    = key("auth.emailAlreadyRegistered")
  val rateLimited: String               = key("auth.rateLimited")
  val emailNotVerified: String          = key("auth.emailNotVerified")
  val verificationTokenInvalid: String  = key("auth.verificationTokenInvalid")
  val verificationSendFailed: String    = key("auth.verificationSendFailed")
  val lastCredential: String            = key("auth.lastCredential")
  val passwordResetTokenInvalid: String = key("auth.passwordResetTokenInvalid")
  val passwordResetSendFailed: String   = key("auth.passwordResetSendFailed")
  val captchaRequired: String           = key("auth.captchaRequired")
  val captchaFailed: String             = key("auth.captchaFailed")

  // -- Social sign-in --------------------------------------------------------------------------
  // `oauthFailed` takes the provider's reason as {0}; `oauthAccountExists` takes its display name.

  val oauthFailed: String          = key("oauth.failed")
  val oauthAccountExists: String   = key("oauth.accountExists")
  val oauthAlreadyLinked: String   = key("oauth.alreadyLinked")
  val oauthUnknownProvider: String = key("oauth.unknownProvider")
  val oauthUnavailable: String     = key("oauth.unavailable")

  // -- Administration --------------------------------------------------------------------------

  val adminUserNotFound: String   = key("admin.userNotFound")
  val adminSelfDemote: String     = key("admin.selfDemote")
  val adminSelfDelete: String     = key("admin.selfDelete")
  val adminLastCredential: String = key("admin.lastCredential")

  // -- Vocabulary ------------------------------------------------------------------------------

  val wordNotFound: String          = key("words.notFound")
  val wordTagNotFound: String       = key("words.tagNotFound")
  val wordTagExists: String         = key("words.tagExists")
  val wordTagReserved: String       = key("words.tagReserved")
  val wordTranslationExists: String = key("words.translationExists")
  val wordNotOwn: String            = key("words.notOwn")

  /** The main word given for a new word's `word_forms` link is in a different language than the word being added — an
    * inflected/declined form always shares its lemma's language.
    */
  val wordMainWordLanguageMismatch: String = key("words.mainWordLanguageMismatch")

  /** Setting a noun's article was refused: the word already has one, it is not a noun, or its language does not have
    * the gender asked for. One key for all three, because a caller that reached any of them sent a request the screen
    * does not offer — the control appears only on a gendered-language noun with no article yet.
    */
  val wordGenderNotApplicable: String = key("words.genderNotApplicable")

  /** Setting a noun's article would collide: the same word with that article is already its own row, since gender is
    * part of a word's identity. Takes no argument: the screen already shows the word and the article the reader picked,
    * so repeating either in the sentence would only give Hungarian an article to decline.
    */
  val wordGenderConflict: String = key("words.genderConflict")

  /** The bulk-upload file itself failed validation — empty, or over `WordService.maxBulkUploadBytes` — as opposed to
    * anything about the words found inside it, which never fails the request.
    */
  val wordBulkUploadInvalidFile: String = key("words.bulkUploadInvalidFile")

  /** The uploaded tag-export file could not be read as one — not valid JSON, or not version
    * [[gathedge.shared.dto.TagExportFile.currentVersion]], or otherwise malformed.
    */
  val wordTagImportInvalidFile: String = key("words.tagImportInvalidFile")

  /** One or more tags in an import file have names the account already owns, and the request did not say what to do
    * about them. Takes the comma-joined list of clashing names as `{0}`.
    */
  val wordTagImportConflict: String = key("words.tagImportConflict")

  // -- Usage quotas ------------------------------------------------------------------------------
  // Two independent per-account caps (`AppConfig.quotas`): how many tags one account may own, and
  // how many `word_tag_pairs` rows it may own summed across every tag it holds. Each has a soft
  // threshold, which only warns, and a hard one, which blocks — see `WordService.checkQuota`. Both
  // `*Warning` keys take the count reached and the hard limit as `{0}`/`{1}`; both `*Exceeded` keys
  // take only the hard limit, and are phrased so the number never sits beside an article — Hungarian
  // alternates `a`/`az` by the sound that follows, which no placeholder can carry.

  val wordTagQuotaWarning: String   = key("words.tagQuotaWarning")
  val wordTagQuotaExceeded: String  = key("words.tagQuotaExceeded")
  val wordPairQuotaWarning: String  = key("words.pairQuotaWarning")
  val wordPairQuotaExceeded: String = key("words.pairQuotaExceeded")

  // -- Games -------------------------------------------------------------------------------------

  val gameNotFound: String        = key("games.notFound")
  val gameNotOwner: String        = key("games.notOwner")
  val gameTagNotEligible: String  = key("games.tagNotEligible")
  val gameNoTagsSelected: String  = key("games.noTagsSelected")
  val gameNoEligibleWords: String = key("games.noEligibleWords")

  /** Takes [[gathedge.shared.validation.Validation.maxWordLimit]] as `{0}` — one message covers both a non-positive
    * count and one over the ceiling, since "enter a number between 1 and {0}" already reads correctly for either.
    */
  val gameWordLimitInvalid: String = key("games.wordLimitInvalid")

  /** Takes the eligible pool size as `{0}` — a fixed count `>=` the words actually available in the chosen direction.
    */
  val gameWordLimitTooMany: String = key("games.wordLimitTooMany")

  // -- Guest accounts --------------------------------------------------------------------------
  // `codeInvalid` answers an unknown, revoked or malformed transfer code alike, so the code space
  // cannot be probed — the same reasoning as the verification token's single answer.

  val guestCodeInvalid: String = key("guest.codeInvalid")
  val guestNotGuest: String    = key("guest.notGuest")

  // -- Progress sharing --------------------------------------------------------------------------
  // Letting one account read another's game history, on either side's own say-so. `codeInvalid`
  // answers an unknown or revoked share code alike, the same reasoning `guestCodeInvalid` follows.

  val progressShareCodeInvalid: String         = key("progressShares.codeInvalid")
  val progressShareCannotShareWithSelf: String = key("progressShares.cannotShareWithSelf")
  val progressShareAlreadyShared: String       = key("progressShares.alreadyShared")
  val progressShareNotShared: String           = key("progressShares.notShared")

  // -- Groups ----------------------------------------------------------------------------------
  // Classroom-style tag collaboration. `inviteCodeInvalid` answers an unknown or rotated code
  // alike, the same reasoning `guestCodeInvalid` follows. `notFound` covers both "no such tag" and
  // "not the owner and not in its group" for GroupFailure.TagNotFound, reusing `wordTagNotFound`
  // rather than minting a second key for the same meaning.

  val groupNotFound: String          = key("groups.notFound")
  val groupInviteCodeInvalid: String = key("groups.inviteCodeInvalid")
  val groupNotMember: String         = key("groups.notMember")
  val groupNotAdmin: String          = key("groups.notAdmin")
  val groupLastAdmin: String         = key("groups.lastAdmin")
  val groupTagNotOwned: String       = key("groups.tagNotOwned")
  val groupTagAlreadyInGroup: String = key("groups.tagAlreadyInGroup")
  val groupTagNotInGroup: String     = key("groups.tagNotInGroup")

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

  val emailResetSubject: String = key("email.reset.subject")
  val emailResetBody: String    = key("email.reset.body")
}
