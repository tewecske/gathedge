import { test, expect, type Page } from '@playwright/test';

// The vocabulary quiz game, end to end: an account marks a few words for practice, turns them into a shared
// quiz link, and a total stranger plays that link through to a score — with no account of their own to start.
//
// Requires the real stack (see playwright.config.ts). Unlike vocabulary.spec.ts this does not need the
// dictionary sample loaded: the words this test plays with are added through the "word the dictionary does not
// have" form (the same one vocabulary.spec.ts's "Zwetschge" test exercises), typed and translated by the test
// itself, so the expected answer to every prompt is known up front — no dependency on what a particular
// dictionary build happens to contain, and no ambiguity from a shared dictionary already holding another
// entry with the same headword.
//
// What this covers that no other suite can: the whole quiz loop in a real browser, and the guest detour inside
// it. `startPlay` is the first write GameInstancePage makes, so it is where a signed-out visitor is minted a
// guest account (see GameInstancePage's doc comment on `asReader`) — that only happens through a real request
// with a real cookie jar, which is why it belongs here rather than in a frontend spec. Opening the shared link
// and previewing the play-variant picker (GameApiClient.playSetup, an `optionalUser` read like `get`) must NOT
// mint a guest — this suite asserts no session cookie exists until "Start" is actually clicked.
//
// Rewritten for the play-time variant picker (game-variants-redesign): word-count/randomize/articles controls
// moved off GameSetupPage (creation) and onto GameInstancePage (play), chosen fresh each play rather than fixed
// at creation. This suite now also exercises the swap-direction arrow, the play-time word-limit control, and the
// "words I haven't played" preference actually skewing a narrower second play toward words the first play never
// touched — plus the owner's results listing showing both plays as distinct rows.

const unique = Date.now();
const ownerEmail = `e2e-game-${unique}@example.com`;
const ownerPassword = 'password123';
const tagName = `quizgame${unique}`;

// Four words with a known, unambiguous German -> Hungarian pair each. GameService.nextPrompt shows a word's
// bare text (no article) and scores the answer case-insensitively, so the prompt a player sees is exactly
// `term` and the one correct answer is exactly `hu` (once lower-cased). Four, not three: the play-preference
// test below needs a first play to leave at least one word untouched for a second, narrower play to skew toward.
const words: Array<{ term: string; hu: string }> = [
  { term: `Quiz${unique}Apfel`, hu: `alma${unique}` },
  { term: `Quiz${unique}Brot`, hu: `kenyer${unique}` },
  { term: `Quiz${unique}Katze`, hu: `macska${unique}` },
  { term: `Quiz${unique}Hund`, hu: `kutya${unique}` },
];

test.describe.configure({ mode: 'serial' });

let page: Page;
let gameUrl: string;
let gameSlug: string;

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
});

test.afterAll(async () => {
  await page.close();
});

test('an account signs up to build the quiz', async () => {
  await page.goto('/en/sign-up');
  await page.locator('input[type=email]').fill(ownerEmail);
  await page.locator('input[type=password]').fill(ownerPassword);
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page).toHaveURL(/\/en\/$/);
});

test('a tag collects four words, each with its Hungarian translation marked', async () => {
  await page.goto('/en/words?lang=de&target=hu');

  // Creating a tag collects into it immediately (WordsPage/WordCollect's rule), so every word added below
  // files under it with no further control to set.
  await page.locator('input[placeholder="lesson1"]').fill(tagName);
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByLabel('Collect into').locator('option:checked')).toHaveText(new RegExp(`^${tagName}`));

  for (const { term, hu } of words) {
    await page.goto('/en/words?lang=de&target=hu');
    await page.locator('input[type=search]').fill(term);
    await expect(page.getByText(`Add “${term}”`)).toBeVisible();

    // Adding a word with a translation marks that translation for practice under the tag it is filed in —
    // the one write that both creates the word and makes it an eligible game prompt in one request. It also
    // pairs both directions (WordRepository.pairTranslation writes one row per direction), which is what makes
    // the reverse (Hungarian -> German) direction eligible too, further down.
    const form = page.locator('.card', { hasText: `Add “${term}”` });
    await form.getByLabel('Hungarian').fill(hu);
    await form.getByRole('button', { name: 'Add' }).click();

    await expect(page).toHaveURL(/\/en\/words\/\d+$/);
    await expect(page.getByText(hu)).toBeVisible();
  }
});

test('the setup screen is short — only language pair and tags — and every game records its plays', async () => {
  await page.goto('/en/games/vocabulary-quiz');

  // Default language pair is German -> Hungarian already (GameSetupPage's own default), matching the direction
  // the tag above was marked in.
  const tagRow = page.locator('label', { hasText: tagName });
  await expect(tagRow).toBeVisible();
  await expect(tagRow).toContainText(`${tagName} (${words.length})`);

  // The word-count/randomize/articles controls this screen used to have all moved to the play-time picker on
  // GameInstancePage — confirm none of them survive here. Neither does the old "Track results" opt-in: every
  // game records its plays now.
  await expect(page.getByText('Include definite articles')).toHaveCount(0);
  await expect(page.getByText(/Randomize/i)).toHaveCount(0);
  await expect(page.getByText('Track results')).toHaveCount(0);
  await expect(page.locator('input[type=number]')).toHaveCount(0);

  await tagRow.locator('input[type=checkbox]').check();

  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await expect(page).toHaveURL(/\/en\/g\/[a-z0-9-]+$/);
  gameUrl = page.url();
  gameSlug = gameUrl.split('/').pop() ?? '';
  expect(gameSlug).not.toBe('');

  // The owner lands straight on the instance page it just created (GameSetupPage's own pushState) and is
  // recognized as the owner in this same browser — the "View results" link shows for the game's owner
  // (GameInstancePage.renderNameHeader).
  await expect(page.getByRole('link', { name: 'View results' })).toBeVisible();
});

test('a stranger with no account plays the shared link, exercising the variant picker, then plays again narrower', async ({
  browser,
}) => {
  // Two full plays plus the whole picker's worth of assertions comfortably outgrow Playwright's 30s default —
  // this single test intentionally keeps both plays together (same guest, same in-browser answer history
  // driving the second play's "unplayed" narrowing), so it gets a longer budget instead of being split.
  test.setTimeout(60000);

  // A fresh, cookie-free context is a fresh visitor as far as the server is concerned — the same trick
  // vocabulary.spec.ts uses for the transfer-code test, and the only way to reach the guest detour for real.
  const guestContext = await browser.newContext();
  const guestPage = await guestContext.newPage();
  await guestPage.goto(gameUrl);

  // The quiz itself renders with no session at all — GameEndpoints.get mints nobody.
  await expect(guestPage.getByRole('button', { name: 'Start' })).toBeVisible();

  // The play-time variant picker, in full: direction swap, word-limit select-all/count, an articles toggle
  // (both languages of this pair include German, since the pair is German<->Hungarian), and the three-way
  // preference select — none of which appeared on the setup screen above.
  await expect(guestPage.getByTitle('Swap languages')).toBeVisible();
  const langSpans = guestPage.locator('span.font-medium');
  await expect(langSpans).toHaveCount(2);
  await expect(langSpans.nth(0)).toHaveText('German');
  await expect(langSpans.nth(1)).toHaveText('Hungarian');

  await expect(guestPage.getByText('How many words')).toBeVisible();
  const selectAllCheckbox = guestPage.locator('label', { hasText: 'Use every eligible word' }).locator('input[type=checkbox]');
  await expect(selectAllCheckbox).toBeChecked();

  const articlesRow = guestPage.locator('label', { hasText: 'Include definite articles' });
  await expect(articlesRow).toBeVisible();
  await expect(articlesRow).toContainText('Show "der"/"die"/"das" with German nouns in the quiz');

  await expect(guestPage.getByText('Which words')).toBeVisible();
  await expect(guestPage.locator('select option', { hasText: 'All words' })).toHaveCount(1);
  await expect(guestPage.locator('select option', { hasText: "Words I haven't played" })).toHaveCount(1);
  await expect(guestPage.locator('select option', { hasText: "Words I've made the most mistakes with" })).toHaveCount(1);

  // Preview list reflects the full pool before any play — GameApiClient.playSetup fetched right on load.
  await expect(guestPage.getByText('Eligible words')).toBeVisible();
  await expect(guestPage.getByText(`${words.length} words`)).toBeVisible();

  // The point of this fix: merely opening the link and having the preview load must mint no guest account.
  // `GET /api/games/{slug}` and `GET /api/games/{slug}/plays/setup` are both `optionalUser` reads.
  const cookiesBeforeStart = await guestContext.cookies();
  expect(cookiesBeforeStart.some((c) => c.name === 'session')).toBe(false);

  // Exercise the swap arrow itself: it flips the displayed pair for this play, and reverts cleanly. Not
  // disabled, since the reverse direction has an eligible pool too (both-directions pairing above).
  const swapButton = guestPage.getByTitle('Swap languages');
  await expect(swapButton).toBeEnabled();
  await swapButton.click();
  await expect(langSpans.nth(0)).toHaveText('Hungarian');
  await expect(langSpans.nth(1)).toHaveText('German');
  await swapButton.click();
  await expect(langSpans.nth(0)).toHaveText('German');
  await expect(langSpans.nth(1)).toHaveText('Hungarian');

  // First play: narrow to 3 of the 4 eligible words (direction unswapped, preference "All"), so exactly one
  // word is left untouched by this player in this direction for the second play to skew toward.
  await selectAllCheckbox.uncheck();
  const wordLimitInput = guestPage.locator('input[type=number]');
  await wordLimitInput.fill('3');

  await guestPage.getByRole('button', { name: 'Start' }).click();

  // Starting a play is the first write, so it is what mints the guest account here — confirmed above that
  // nothing before this click did. It is also what moves the play loop onto its own route.
  await expect(guestPage).toHaveURL(/\/en\/g\/[a-z0-9-]+\/play\/\d+$/);
  await expect(guestPage.getByRole('heading', { name: 'Your words are saved on this device' })).toBeVisible();

  const firstPlaySeen: string[] = [];
  for (let i = 0; i < 3; i++) {
    // Scoped to the prompt's own heading class: the guest banner above it is an `h2` too ("Your words are saved on
    // this device"), and a bare `h2` locator matches both once the guest account exists.
    const heading = guestPage.locator('h2.text-xl');
    const promptText = (await heading.textContent())?.trim() ?? '';
    const match = words.find((w) => w.term === promptText);
    expect(match, `unexpected quiz prompt: "${promptText}"`).toBeTruthy();
    firstPlaySeen.push(match!.term);

    await guestPage.getByPlaceholder('Type the translation').fill(match!.hu);
    await guestPage.getByRole('button', { name: 'Submit' }).click();

    if (i < 2) {
      // Prompts arrive in a random order (GameService.nextPrompt picks one at random from what is left), so the
      // only thing worth waiting for is that this word is no longer the one shown — not which word replaces it.
      await expect(heading).not.toHaveText(promptText);
    } else {
      // The last correct answer ends the play; the prompt card is replaced by the results screen.
      await expect(guestPage.getByText('Quiz complete')).toBeVisible();
    }
  }

  // maxPointsPerWord (2) per word, three words this play.
  await expect(guestPage.getByText('Score: 6 / 6')).toBeVisible();
  expect(firstPlaySeen).toHaveLength(3);

  const untouched = words.find((w) => !firstPlaySeen.includes(w.term));
  expect(untouched, 'expected exactly one word left unanswered by the first play').toBeTruthy();

  // Second play: same direction, but "Words I haven't played" narrowed to exactly 1 — the per-direction answer
  // history this player just built means only `untouched` qualifies, so the preview (fetched fresh on the
  // preference change, before starting) should show exactly that one word, and the prompt itself must be it.
  //
  // Reached by revisiting the shared link itself, not the finished screen's own "Play again" — since the play
  // loop split onto its own route (`Page.GamePlay`), "Play again" now skips the picker entirely and starts a new
  // play under the exact same variant just played (see the third play, below), so it is no longer how a player
  // reconfigures before a play. Only a fresh visit to the picker (`Page.GameInstance`) offers that stop.
  await guestPage.goto(gameUrl);
  await expect(guestPage).toHaveURL(new RegExp(`/en/g/${gameSlug}$`));
  await expect(guestPage.getByRole('button', { name: 'Start' })).toBeVisible();
  await expect(guestPage.getByTitle('Swap languages')).toBeVisible();
  await expect(guestPage.getByText('How many words')).toBeVisible();
  await expect(guestPage.getByText('Which words')).toBeVisible();

  // `GET /api/games/{slug}/plays/setup` answers the whole eligible pool re-ordered by preference (priority
  // sampling, not a hard filter — see the design doc), so the preview's own count stays at the pool size (4)
  // regardless of which preference is picked; `renderPreviewList` never lists individual words, only the count.
  // The one place the "unplayed" narrowing is actually observable is the sampled prompt itself, below.
  await guestPage.locator('select').selectOption('unplayed');
  await expect(guestPage.getByText(`${words.length} words`)).toBeVisible();

  await selectAllCheckbox.uncheck();
  await wordLimitInput.fill('1');
  await guestPage.getByRole('button', { name: 'Start' }).click();

  // Starting moved the play loop onto its own route — the picker is gone from the URL, replaced by the play id.
  await expect(guestPage).toHaveURL(/\/en\/g\/[a-z0-9-]+\/play\/\d+$/);
  const secondPlayUrl = guestPage.url();

  const heading = guestPage.locator('h2.text-xl');
  await expect(heading).toHaveText(untouched!.term);
  await guestPage.getByPlaceholder('Type the translation').fill(untouched!.hu);
  await guestPage.getByRole('button', { name: 'Submit' }).click();
  await expect(guestPage.getByText('Quiz complete')).toBeVisible();
  await expect(guestPage.getByText('Score: 2 / 2')).toBeVisible();
  await expect(guestPage.locator('table tbody tr')).toHaveCount(1);

  // Third play: "Play again" itself, now that the play loop is its own page. It skips the picker entirely and
  // reuses the just-finished play's exact variant (unswapped, limit 1, "Words I haven't played") via
  // `GameReplay.start` — landing straight on a new prompt at a new play id, never showing "Start" or the swap
  // arrow. By now every word has been answered at least once (the first and second plays together cover all
  // four), so "unplayed" priority has nothing left to prefer and the sampled word is not asserted — only that
  // the loop actually started.
  await guestPage.getByRole('button', { name: 'Play again' }).click();
  // The regex alone would also match `secondPlayUrl` (same shape, different id), and resolve trivially without
  // waiting for the actual navigation — assert against the exact prior URL first so this genuinely waits for it.
  await expect(guestPage).not.toHaveURL(secondPlayUrl);
  await expect(guestPage).toHaveURL(/\/en\/g\/[a-z0-9-]+\/play\/\d+$/);
  await expect(guestPage.getByRole('button', { name: 'Start' })).toHaveCount(0);
  await expect(guestPage.getByTitle('Swap languages')).toHaveCount(0);

  const thirdPromptText = (await heading.textContent())?.trim() ?? '';
  const thirdMatch = words.find((w) => w.term === thirdPromptText);
  expect(thirdMatch, `unexpected quiz prompt: "${thirdPromptText}"`).toBeTruthy();
  await guestPage.getByPlaceholder('Type the translation').fill(thirdMatch!.hu);
  await guestPage.getByRole('button', { name: 'Submit' }).click();
  await expect(guestPage.getByText('Quiz complete')).toBeVisible();
  await expect(guestPage.getByText('Score: 2 / 2')).toBeVisible();

  await guestContext.close();
});

test("the owner's results listing shows all three plays as distinct rows", async () => {
  await page.goto(`/en/games/${gameSlug}/results`);

  await expect(page.getByRole('heading', { name: 'Results' })).toBeVisible();
  const rows = page.locator('table tbody tr');
  await expect(rows).toHaveCount(3);

  // Every play was the same anonymous guest (playerEmail absent), so every row badges "Guest" — what actually
  // distinguishes the rows is the Variant column (GameResultsPage.renderRow, Labels.wordPreference): the first
  // play used the default "All words" preference; the second and third ("Play again" reusing it) both narrowed
  // to "Words I haven't played". Direction is German -> Hungarian for all three (never swapped for any play).
  await expect(rows.filter({ hasText: 'Guest' })).toHaveCount(3);

  const wideRow = rows.filter({ hasText: '6 / 6' });
  await expect(wideRow).toHaveCount(1);
  await expect(wideRow).toContainText('3');
  await expect(wideRow).toContainText('German → Hungarian · All words');

  // Two rows share this shape: the "unplayed"-narrowed play and its own "Play again" replay.
  const narrowRows = rows.filter({ hasText: '2 / 2' });
  await expect(narrowRows).toHaveCount(2);
  await expect(narrowRows.first()).toContainText('1');
  await expect(narrowRows.first()).toContainText("German → Hungarian · Words I haven't played");

  // Opening one row's detail modal works, shows its own answer history, and the same variant line.
  await narrowRows.first().getByRole('button', { name: 'View' }).click();
  await expect(page.getByRole('heading', { name: 'Play result' })).toBeVisible();
  await expect(page.locator('.modal-box table tbody tr')).toHaveCount(1);
  await expect(page.locator('.modal-box.max-w-2xl')).toContainText("German → Hungarian · Words I haven't played");
  await page.getByRole('button', { name: 'Close' }).click();
});
