package gathedge.backend.db

/** `emailVerifiedAt` is `None` until the address is proven — either by following a verification link or by arriving
  * from a provider that asserts `email_verified`. Whether that actually blocks a password login is
  * `app.require-email-verification`; the column is filled in either way.
  *
  * `email` is `None` exactly when `isGuest` is true — an account minted without credentials so that the vocabulary
  * needs no sign-up. A NULL address is what lets any number of guests coexist under the column's `UNIQUE` index, and it
  * is why `findByEmail` cannot accidentally return one: nothing equals NULL.
  *
  * `username` is the account's other name to sign in by, stored lowercased and unique across every account — a guest
  * gets a random one when it is minted. `displayName` is what the account is called on screen and is matched on by
  * nothing at all. Both are `None` for an account that has never filled them in.
  */
final case class UserRow(
  id: Long,
  email: Option[String],
  passwordHash: Option[String],
  isAdmin: Boolean,
  theme: String,
  locale: String,
  createdAt: Long,
  emailVerifiedAt: Option[Long],
  isGuest: Boolean,
  username: Option[String] = None,
  displayName: Option[String] = None,
)

/** One external identity linked to a user. `provider` holds [[gathedge.backend.service.OAuthProvider]]'s wire name and
  * `subject` the provider's own stable identifier for the user; the pair is unique. `email` is whatever the provider
  * reported at link time, kept for display only — it is never used to match an account, see
  * `AuthService.loginWithOAuth`.
  */
final case class OAuthIdentityRow(
  id: Long,
  userId: Long,
  provider: String,
  subject: String,
  email: Option[String],
  createdAt: Long,
)

final case class SessionRow(id: String, userId: Long, createdAt: Long, expiresAt: Long, revokedAt: Option[Long])

/** A single-use proof-of-address link. `consumedAt` set means it has already been redeemed; rows are pruned by
  * [[gathedge.backend.service.SessionReaper]] once they are past `expiresAt`.
  */
final case class EmailVerificationTokenRow(
  id: Long,
  userId: Long,
  token: String,
  createdAt: Long,
  expiresAt: Long,
  consumedAt: Option[Long],
)

/** A single-use "forgot password" link, the same shape as [[EmailVerificationTokenRow]]. `consumedAt` set means it has
  * already been redeemed; rows are pruned by [[gathedge.backend.service.SessionReaper]] once past `expiresAt`.
  */
final case class PasswordResetTokenRow(
  id: Long,
  userId: Long,
  token: String,
  createdAt: Long,
  expiresAt: Long,
  consumedAt: Option[Long],
)

/** One sign-in attempt, successful or not. The history behind the in-memory rate limiter, which knows only the current
  * 15-minute window and forgets it on restart.
  *
  * `email` is what the caller typed, normalized the same way [[gathedge.backend.service.RateLimitKey.email]] normalizes
  * it, so an attempt against an address that has no account is still attributable. `userId` is `None` in exactly that
  * case, and becomes `None` again if the account is later deleted — the row outlives it deliberately.
  */
final case class LoginAttemptRow(
  id: Long,
  email: String,
  userId: Option[Long],
  ip: Option[String],
  outcome: String,
  createdAt: Long,
)

/** One API request, written by `RouteSupport.usageTracking` on every call, session or none. `route` is normalized
  * rather than the raw path — see the note on the migration that creates this table — so aggregation groups requests by
  * endpoint shape instead of by exact URL. `userId` is `None` for an anonymous or invalid-session caller, and becomes
  * `None` again if the account is later deleted, like [[LoginAttemptRow.userId]].
  */
final case class UsageEventRow(
  id: Long,
  createdAt: Long,
  method: String,
  route: String,
  status: Int,
  userId: Option[Long],
  ip: Option[String],
)

/** The queryable half of the `security` logger: one row per administrator action, written by the same
  * `AdminService.audit` call that emits the log line.
  *
  * `actorEmail` is a snapshot rather than a join, because the row has to still name who acted after that account is
  * deleted. `detail` is prose for an administrator to read and must never carry a credential — see
  * [[QuillRepository.logged]] for the same rule applied to log lines.
  */
final case class AuditLogRow(
  id: Long,
  occurredAt: Long,
  actorUserId: Option[Long],
  actorEmail: Option[String],
  action: String,
  targetType: Option[String],
  targetId: Option[String],
  detail: Option[String],
  ip: Option[String],
)

/** One lexical unit, shared by every account.
  *
  * `textNorm` is the lowercased form and the identity key the `UNIQUE` index and `findWord` use. `textSearch` is
  * `textNorm` with accents folded off (see [[TextSearch.fold]]) and is the only column the search box's `LIKE` touches
  * — kept separate so an accented and an unaccented spelling still count as different words, while the search box still
  * finds either from a keyboard with no accents on it. `gender` and `frequencyRank` are never null — `""` and a large
  * sentinel stand in — because a NULL is distinct in a `UNIQUE` index on both dialects and sorts in a dialect-dependent
  * place; see the migration's comment. `createdBy` is `None` for an imported row, and becomes `None` again if the
  * account that typed one is deleted: a word other people have tagged outlives its author.
  *
  * `isForm` is derived, not authoritative: it is true exactly when a `word_forms` row names this word as the form side,
  * and `word_forms` stays the truth. It exists so the listing's "main words only" filter is a row predicate rather than
  * a correlated subquery — see `V20__words_is_form.sql`. It defaults to `false` because a word starts out belonging to
  * nobody's inflection table, and only [[WordRepository.insertForms]]/`.deleteWordForms` may change it.
  */
final case class WordRow(
  id: Long,
  language: String,
  text: String,
  textNorm: String,
  partOfSpeech: String,
  gender: String,
  frequencyRank: Int,
  source: String,
  createdBy: Option[Long],
  createdAt: Long,
  textSearch: String,
  isForm: Boolean = false,
)

/** One direction of a translation. Both directions are stored, so every read is a filter on `sourceWordId` and the
  * practice screen can prompt either way round.
  *
  * `origin` is `dictionary` for what the dictionary asserts, `pivot` for a German–Hungarian pair derived through a
  * shared English sense, and `user` for one somebody typed — which is the only case where `createdBy` is set.
  */
final case class WordTranslationRow(
  id: Long,
  sourceWordId: Long,
  targetWordId: Long,
  origin: String,
  createdBy: Option[Long],
  createdAt: Long,
)

/** One relation between two `words` rows: `formWordId` is a form of `lemmaWordId`, tagged by `relation` -- a
  * lower-cased, sorted, comma-joined serialisation of wiktextract's own tag set for that form (`"plural"`,
  * `"dative,definite,plural"`), following the same free-form-string convention [[WordTranslationRow.origin]] does.
  * Never a closed enum: German case x number x definiteness, and Hungarian's case system, are both larger than any list
  * this application would maintain by hand.
  */
final case class WordFormRow(
  id: Long,
  lemmaWordId: Long,
  formWordId: Long,
  relation: String,
  createdAt: Long,
)

/** A label one account puts on words. `nameNorm` is the lowercased form the per-account uniqueness is on.
  *
  * `groupId` defaults to `None` so every existing positional construction of this row (test fixtures included) keeps
  * compiling without naming it. Set, it means every member of that group — not only `userId` — may edit this tag's
  * content; see `WordService.requireEditableTag`. `userId` is unaffected either way: the tag still has exactly one
  * owner, who alone may rename or delete it.
  */
/** `sourceLanguage`/`targetLanguage` are the tag's fixed language pair (`WordLanguage.code` strings), decided the first
  * time a row is added and locked afterwards — see `WordRepository.setTagLanguages`. They tell the editor and a later
  * import which side of a bidirectional `word_tag_pairs` row is the "source". `None` on a freshly minted empty tag and
  * on every tag that predates the unified editor. Defaulted, like `groupId`, so positional construction keeps
  * compiling.
  */
final case class TagRow(
  id: Long,
  userId: Long,
  name: String,
  nameNorm: String,
  createdAt: Long,
  groupId: Option[Long] = None,
  sourceLanguage: Option[String] = None,
  targetLanguage: Option[String] = None,
)

/** A classroom-style group of accounts collaborating on shared tags. `inviteCode` is a bearer credential — like
  * [[GuestClaimCodeRow]]'s — and must never reach a log line.
  */
final case class GroupRow(
  id: Long,
  name: String,
  nameNorm: String,
  inviteCode: String,
  createdBy: Option[Long],
  createdAt: Long,
)

/** One account's standing (`role`: `"admin"` or `"member"`, see `GroupRole.code`) on one group's roster. */
final case class GroupMemberRow(id: Long, groupId: Long, userId: Long, role: String, createdAt: Long)

/** One word carrying one tag — and, since a tag belongs to exactly one account, the whole of what "this word is in my
  * vocabulary" means.
  */
/** `imported` marks a membership a bulk import wrote rather than one added by hand — scoped to this (word, tag) row.
  * Defaulted so positional construction (test fixtures included) keeps compiling; every row that predates the unified
  * editor reads as `false`, which is what it was.
  *
  * `comment` is the note the reader wrote beside the word in their own list — the `(növény)` of `levél (növény)`. It is
  * here rather than on [[WordRow]] because `words` is shared by every account: which sense of `levél` one reader means
  * is not a fact about the word.
  */
final case class WordTagRow(
  id: Long,
  wordId: Long,
  tagId: Long,
  createdAt: Long,
  imported: Boolean = false,
  comment: Option[String] = None,
)

/** One translation of one word, marked as a practice answer inside one tag.
  *
  * A tag says the word is being learned; this says which of its translations the practice screen should check against,
  * which is a different question — most words have several translations and only some are the sense being learned.
  *
  * Both directions are stored, like [[WordTranslationRow]]: the row `(Haus, lesson1, ház)` is always accompanied by
  * `(ház, lesson1, Haus)`, so a prompt works either way round with no union. Both words also carry the tag, since a
  * pair whose answer is not itself collected is a question with a missing half — which is why this is written through
  * `WordRepository.pairTranslation` rather than a bare insert.
  *
  * `exact` marks a pair a bulk import matched exactly — the word and its dictionary translation were both in the
  * uploaded text. History, not state; defaulted `false` for every hand-marked pair and every pair that predates the
  * unified editor.
  */
final case class WordTagPairRow(
  id: Long,
  wordId: Long,
  tagId: Long,
  translationWordId: Long,
  createdAt: Long,
  exact: Boolean = false,
)

/** One row of the unified tag editor, assembled by `WordRepository.tagEntries`: the source word, its marked answer
  * (absent for an "unmatched" row), whether a bulk import wrote the membership, and whether it wrote the pair as an
  * exact match. Not a table — a projection the editor and its filters read.
  *
  * The two comments come from the two `word_tags` memberships, one per side, because either cell of an imported line
  * may have carried a note and they say different things.
  */
final case class TagEntryRow(
  source: WordRow,
  target: Option[WordRow],
  imported: Boolean,
  exact: Boolean,
  comment: Option[String] = None,
  targetComment: Option[String] = None,
)

/** A guest account's transfer code. The `code` column *is* the bearer credential, like `SessionRow.id`: it must never
  * reach a log line, and it is answered to its owner exactly once, when it is minted.
  */
final case class GuestClaimCodeRow(
  id: Long,
  userId: Long,
  code: String,
  createdAt: Long,
  lastUsedAt: Option[Long],
  revokedAt: Option[Long],
)

/** One quiz, scoped to a language pair and built from the tags in [[GameTagRow]]. Nothing here changes after creation —
  * word count, direction, article display and word preference are all play-time choices now, carried per-play on
  * [[GamePlayRow]] instead. See the "game variants redesign" design doc.
  *
  * `slug` is generated once at creation and never changes — the permanent key a share link is built from, sized and
  * typed like [[PasswordResetTokenRow.token]] / [[GuestClaimCodeRow.code]]. `name` is the opposite: cosmetic, free to
  * edit at will, which is why the two are separate columns rather than one renameable field.
  *
  * Every game's plays are readable by its owner (`GameService.listPlays`/`getPlayDetail`); [[GamePlayRow]] and
  * [[GamePlayAnswerRow]] are written by every play, and there is no per-game opt-in gating that read.
  */
final case class GameRow(
  id: Long,
  ownerUserId: Long,
  slug: String,
  name: String,
  sourceLanguage: String,
  targetLanguage: String,
  createdAt: Long,
  updatedAt: Long,
)

/** One tag a game draws its words from. A game can span several tags, so this is a join table exactly like
  * [[WordTagRow]], not a single column on [[GameRow]].
  */
final case class GameTagRow(id: Long, gameId: Long, tagId: Long)

/** One account's "favorite" mark on one game — a join table exactly like [[GameTagRow]], `UNIQUE (user_id, game_id)`.
  * Backs the games listing's heart toggle, its "my favorites" filter, and its per-game like count.
  */
final case class GameFavoriteRow(id: Long, userId: Long, gameId: Long, createdAt: Long)

/** One attempt at a game, by one account, under one play-time variant.
  *
  * `score`, `maxScore` and `wordCount` are denormalized here rather than derived from [[GamePlayAnswerRow]] on every
  * read — a play is read far more often than written to, and all three are cheap to maintain incrementally as answers
  * come in. `wordCount` and `maxScore` are fixed at the moment the play starts; `score` is the one column that changes
  * as it progresses. `finishedAt` is `None` for a play still in progress and set once, when it completes — there is no
  * separate "abandoned" state.
  *
  * `sourceLanguage`/`targetLanguage`/`wordLimit`/`includeDefiniteArticles`/`wordPreference` are the variant this
  * specific play ran under — a snapshot, not a live reference to the (now immutable) base game, since a play may have
  * swapped direction or picked a narrower/differently-preferenced sample than another play of the same game.
  * `wordLimit` keeps its old `games.word_limit` meaning: `None` for "every eligible word", `Some(n)` for "sampled
  * exactly n (or fewer, if the pool was smaller)". `wordPreference` holds a [[gathedge.shared.domain.WordPreference]]
  * code. `mode` holds a [[gathedge.shared.domain.GameMode]] code — whether the play was typed or clicked. These six
  * default to English/English/no limit/articles on/"all"/"typing" only so pre-migration test fixtures that construct a
  * `GamePlayRow` positionally keep compiling — `GameService.startPlay` always supplies real values.
  */
final case class GamePlayRow(
  id: Long,
  gameId: Long,
  playerUserId: Long,
  score: Int,
  maxScore: Int,
  wordCount: Int,
  startedAt: Long,
  finishedAt: Option[Long],
  sourceLanguage: String = "en",
  targetLanguage: String = "en",
  wordLimit: Option[Int] = None,
  includeDefiniteArticles: Boolean = true,
  wordPreference: String = "all",
  mode: String = "typing",
)

/** One word pair asked and answered inside one play — the per-word-pair progression record the whole feature is built
  * on. There is no separate stats table: a play's own score fields are the per-play summary, and a later feature
  * aggregates this row across plays for anything more.
  *
  * Both `wordId` and `translationWordId` are stored, for the same reason [[WordTagPairRow]] stores both: a word usually
  * has several translations, and this row has to say which specific sense was tested, not merely which word was on
  * screen. Unlike [[WordTagPairRow]], these two columns deliberately do NOT cascade from `words` — see the migration's
  * comment: this table is a permanent scored history record, and a play's score must never silently change because a
  * dictionary row was later removed.
  */
final case class GamePlayAnswerRow(
  id: Long,
  playId: Long,
  wordId: Long,
  translationWordId: Long,
  position: Int,
  userAnswer: String,
  outcome: String,
  points: Int,
  answeredAt: Long,
)

/** One word pair sampled into one specific play, written once at `startPlay` and never touched again — the fixed set
  * [[GameRepository.wordPairsOf]] reads back for the rest of that play, instead of [[GameRepository.eligibleWordPairs]]
  * being recomputed live on every call. For a play with no `GamePlayRow.wordLimit`, this ends up holding the game's
  * entire eligible pool at the moment the play started; for a limited play, the sampled subset.
  *
  * Like [[GamePlayAnswerRow]]'s `wordId`/`translationWordId`, these two deliberately do NOT cascade from `words` — see
  * the migration's comment: a play's word set is fixed history, not current dictionary state.
  */
final case class GamePlayWordRow(id: Long, playId: Long, wordId: Long, translationWordId: Long)

/** A progress-sharing code: the bearer credential a sharer mints and hands to whoever they want reading their game
  * history. The `code` column *is* the credential, exactly like [[GuestClaimCodeRow.code]] — never logged — and the
  * same reissue/revoke shape: minting is idempotent (the same code comes back until revoked) and redeeming does not
  * consume it, so more than one viewer may redeem the same code.
  */
final case class ProgressShareCodeRow(
  id: Long,
  userId: Long,
  code: String,
  createdAt: Long,
  lastUsedAt: Option[Long],
  revokedAt: Option[Long],
)

/** One (sharer, viewer) grant: `viewerUserId` may read `sharerUserId`'s game history across every game. */
final case class ProgressShareRow(id: Long, sharerUserId: Long, viewerUserId: Long, createdAt: Long)
