# Tag-creation page

Feature summary for branch `feat/tag-creation-page`.

## What this is

A screen where a signed-in reader builds a tag as an **ordered list of bilingual pairs** and saves the
tag and every pair in one request. The old path was create-an-empty-tag first, then file pairs into it
one by one. The new screen turns that into a single authoring flow: type a source word, pick it from
the dictionary, and the target input offers its known translations. Pick one and the pair lands in a
list below while the loop restarts.

Two sides of a pair may be **words the dictionary does not have yet**. The page types them out, and the
server creates them on the fly.

## The page

- `TagCreatePage`, routed at `/tags/new`, listed in `AppShell` navigation, named `TagCreate` in
  `AppRouter`.
- A single joined part-of-speech radio selector sits above the inputs and applies to any word not in
  the dictionary, so typing a long list stays fast — no per-word popup. It also filters the live
  autocomplete searches.
- For a German noun, a der/die/das picker sits in front of the German input, exactly like the game's
  answer input. The picked article becomes part of the typed text (the input keeps "das Haus"); the
  search and the new-word creation strip it again, and the article sets the noun's gender.
- Both inputs autocomplete against the dictionary. The dropdown shows at most 5 matches, ranks
  exact > prefix > form-vs-lemma, marks existing words "in dictionary", and offers the typed text as a
  "new" word when nothing matches. Arrow keys move the highlight, Enter/Tab accept.
- The pairs table lists every committed pair with a per-pair part-of-speech selector and a visible
  remove button. Languages are locked once the first pair is committed. The swap button only moves
  focus back to the filled input while a pair is half-typed, so swapping never clears work.
- An empty target input presses Enter/Tab to return to the source input, not to save.

## The write

`POST /api/tags/with-pairs`, declared once in `WordEndpoints`, body
`CreateTagWithPairsRequest(name, pairs: List[TagPairInput])`, each side a
`TagPairWord.Existing(id)` or `TagPairWord.New(language, text, partOfSpeech, gender)`.
Answers `201 TagResponse` (`tag`, `warning`).

`WordService.createTagWithPairs` does, in order:

1. Validates and normalises the name (same rules as `createTag`: 400 for blank/over-width/reserved,
   case-insensitive 409 for a duplicate).
2. Checks the tag quota and the pair quota **together, before any write** — a request that would cross
   a hard threshold writes nothing, so no half-built tag is left behind. Soft crossings succeed with a
   warning; the tag warning wins over the pair warning when both fire.
3. **Checks** every pair's two sides without writing (`checkPair`/`checkWord`): an `Existing` side must
   name a real word (404 otherwise), a `New` side's text must validate (400 otherwise).
4. Only then **creates** what is missing (`createPair`/`createWord`) and writes the tag and all its
   pairs as **one unit of work**.

Steps 3 and 4 are two passes on purpose. A `New` word is itself a write, so resolving pair one's new
word before discovering pair two's dead `Existing` id would leave that word in the dictionary behind a
request that answered 404.

### The atomic write

`WordRepository.createTagWithPairs` inserts the tag row and every `word_tags`/`word_tag_pairs` row in
a single transaction, through a `linkPair` helper shared with `pairTranslation` (two word-tag links
plus both directed pair rows per pair). This was a real bug, not a formality:

- The first version inserted the tag with `insertTag`, then wrote the pairs in a separate
  `pairTranslation` transaction.
- On Postgres the pair rows' `word_tags.tag_id` foreign key could not see the just-inserted tag, so
  every request answered 500 (`word_tags_tag_id_fkey`).
- SQLite enforces no foreign keys, so the whole suite passed regardless — exactly the Postgres-only
  trap `CLAUDE.md` warns about.
- The fix folds the tag and its pairs into one transaction, matching how `copyTag` already worked, and
  adds `tagCreationSpec` to `WordServiceSpec`.

The tag-creation request also differs from `createTag` + `selectPair` per pair in that a pair needs no
`word_translations` edge: the reader may pair any two words they chose.

## Errors

| Status | `error.key` | Means |
| --- | --- | --- |
| 400 | — | codec/name error, or a `New` side whose text fails validation |
| 401 | — | no session |
| 404 | — | `Existing` side names no word |
| 409 | `words.tagExists` | account already has the name (case-insensitive) |
| 409 | `words.tagQuotaExceeded` | account at the tag limit |
| 409 | `words.pairQuotaExceeded` | account at the pair limit |

## i18n

New `ui.tags.*` keys in `UiKeys` and in both `messages.{en,hu}.json`: `create`, `name`,
`namePlaceholder`, `sourcePlaceholder`, `targetPlaceholder`, `pairs`, `partOfSpeech`, `inDictionary`,
`newWord`, `removePair`, `emptyPairs`, `saved`. `create` is the nav label as well as the page heading.
`MessagesSpec` enforces both languages.

## Tests

- `WordServiceSpec.tagCreationSpec`: existing words create a tag with both-direction pairs; a missing
  `Existing` id is 404 and writes nothing; a duplicate name is `DuplicateTag`; `New` words are created
  on the fly and paired both ways; a `New` word earlier in the list is not created when a later pair
  is `NotFound` (the two-pass regression).
- `TagCreatePageSpec` (jsdom): renders the heading/name/pairs/save furniture, offers both word inputs,
  and renders the 5-position part-of-speech selector.
- `OpenApiSpec`: pins the `with-pairs` path and its failures.
