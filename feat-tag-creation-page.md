# Tag-creation page

Feature summary for branch `feat/tag-creation-page`.

## What this is

A screen where a signed-in reader builds a tag as an **ordered list of bilingual pairs** and saves the
tag and every pair in one request. The old path was create-an-empty-tag first, then file pairs into it
one by one. The new screen turns that into a single authoring flow: type a source word, pick it from
the dictionary, and its known translations appear under the target input as chips. Pick one and the
pair lands in a list below while the loop restarts.

**The source word is the only thing the reader starts with.** Picking it from the dictionary *sets*
the pair's part of speech, rather than a part of speech being chosen first and filtering the search.

Two sides of a pair may be **words the dictionary does not have yet**. The page types them out, and the
server creates them on the fly.

## The page

- `TagCreatePage`, routed at `/tags/new`, listed in `AppShell` navigation, named `TagCreate` in
  `AppRouter`.
- **The caret starts in the name field**, the one thing named once rather than per pair, and Enter
  hands it to the source input and the pair loop it never leaves again. Tab is left alone: what
  follows the name is the language pair, decided once and before any typing.
- **The source autocomplete is narrowed by nothing but the text.** Every dictionary match comes back
  and each row carries a part-of-speech badge — which is what tells `der See` from `die See`, and
  `laufen` the verb from `Laufen` the noun. It is a vertical menu, because each row shows two facts.
- **Picking a source word settles the pair's part of speech**, and that part of speech then narrows
  the live target search, strictly: a translation the dictionary filed under another one is added as
  a new word instead.
- **The chosen word's known translations show as chips** under the target input, at most 5, from
  `WordApiClient.get(id)` — the listing's own `WordSummary.translations` is capped at 3 by
  `WordService.translationsPerRow`. They are showing before anything is typed, which is the point.
- **Focus never leaves the target input.** The chips are `tabindex="-1"` buttons inside a `listbox`,
  named to a screen reader through `aria-activedescendant`. Arrow keys (either axis) move the
  highlight, Enter/Tab accepts, Escape empties the candidates so the next Tab leaves the field.
  Typing a letter narrows to the live search with no keystroke lost and no mode to leave.
- **A word the dictionary does not have** is the last completion. Committing one as the *source*
  raises an inline part-of-speech select beside the input, defaulting to noun; a new *target* word
  inherits the pair's. Either is corrected afterwards in the pairs table.
- For a German input a der/die/das picker sits in front of it, exactly like the game's answer input.
  The picked article becomes part of the typed text (the input keeps "das Haus"); the search and the
  new-word creation strip it again. **An article is a gender, and a gender is a noun** — there is no
  part-of-speech test in front of the picker any more. It is hidden on the source side once a word is
  committed, where it could only change what is shown and not what is written.
- **A pair the list already holds is refused**, with a warning naming it, and the inputs reset so the
  loop carries on. Two pairs are the same one when their two displayed sides and their part of speech
  match, case-folded — the displayed text rather than the `TagPairWord` refs, since a word picked from
  the dictionary and the same word typed past an autocomplete that had not answered yet are an
  `Existing` and a `New` ref that read identically on screen. `WordRepository.linkPair` would not have
  written the duplicate twice (each of its four rows is inserted only if absent), but the pair would
  have stood in the table twice, been removable a row at a time, and counted twice against the
  projected pair quota.
- **A saved tag lands on the listing showing itself.** `TagCreatePage.landingQuery` narrows `Page.Words`
  to the new tag id and points it the way the tag was written (source language, target language), and
  the collect tag is stored as the new tag *before* the navigation — `WordsPage`'s `WordCollect` reads
  `storedCollectTag` once, as it is constructed. The tag id is also what keeps the arrival from being
  bare, so `WordsPage` does not override it with the filter this browser remembers.
- The pairs table lists every committed pair with a per-pair part-of-speech selector and a visible
  remove button. Languages are locked once the first pair is committed. The swap button only moves
  focus back to the filled input while a pair is half-typed, so swapping never clears work.
- An empty target input with nothing on offer presses Enter/Tab to return to the source input, not to
  save.

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
`namePlaceholder`, `sourcePlaceholder`, `targetPlaceholder`, `pairs`, `partOfSpeech`, `translations`,
`newWord`, `removePair`, `duplicatePair`, `emptyPairs`, `saved`. `create` is the nav label as well as the page heading.
`partOfSpeech` names three things at once: the autocomplete badge, the inline select and the pairs
table's column. `MessagesSpec` enforces both languages.

## Tests

- `WordServiceSpec.tagCreationSpec`: existing words create a tag with both-direction pairs; a missing
  `Existing` id is 404 and writes nothing; a duplicate name is `DuplicateTag`; `New` words are created
  on the fly and paired both ways; a `New` word earlier in the list is not created when a later pair
  is `NotFound` (the two-pass regression).
- `TagCreatePageSpec` (jsdom): renders the heading/name/pairs/save furniture, offers both word inputs,
  asks for no part of speech before a word has been typed, offers the der/die/das picker for a German
  input whatever the part of speech, refuses a pair the list already holds, and starts the caret in
  the name field, handing it to the source word on Enter. `landingQuery` is pinned as its own case:
  all three narrowings set, nothing else carried, and never `WordQuery.default`.
- `OpenApiSpec`: pins the `with-pairs` path and its failures.
