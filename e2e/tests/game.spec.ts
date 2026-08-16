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
// with a real cookie jar, which is why it belongs here rather than in a frontend spec.

const unique = Date.now();
const ownerEmail = `e2e-game-${unique}@example.com`;
const ownerPassword = 'password123';
const tagName = `quizgame${unique}`;

// Three words with a known, unambiguous German -> Hungarian pair each. GameService.nextPrompt shows a word's
// bare text (no article) and scores the answer case-insensitively, so the prompt a player sees is exactly
// `term` and the one correct answer is exactly `hu` (once lower-cased).
const words: Array<{ term: string; hu: string }> = [
  { term: `Quiz${unique}Apfel`, hu: `alma${unique}` },
  { term: `Quiz${unique}Brot`, hu: `kenyer${unique}` },
  { term: `Quiz${unique}Katze`, hu: `macska${unique}` },
];

test.describe.configure({ mode: 'serial' });

let page: Page;
let gameUrl: string;

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

test('a tag collects three words, each with its Hungarian translation marked', async () => {
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
    // the one write that both creates the word and makes it an eligible game prompt in one request.
    const form = page.locator('.card', { hasText: `Add “${term}”` });
    await form.getByLabel('Hungarian').fill(hu);
    await form.getByRole('button', { name: 'Add' }).click();

    await expect(page).toHaveURL(/\/en\/words\/\d+$/);
    await expect(page.getByText(hu)).toBeVisible();
  }
});

test('the tag is offered on the quiz setup screen, and creating the quiz lands on its shared link', async () => {
  await page.goto('/en/games/vocabulary-quiz');

  // Default language pair is German -> Hungarian already (GameSetupPage's own default), matching the direction
  // the tag above was marked in.
  const tagRow = page.locator('label', { hasText: tagName });
  await expect(tagRow).toBeVisible();
  await expect(tagRow).toContainText(`${tagName} (${words.length})`);
  await tagRow.locator('input[type=checkbox]').check();

  await page.getByRole('button', { name: 'Play', exact: true }).click();
  await expect(page).toHaveURL(/\/en\/g\/[a-z0-9-]+$/);
  gameUrl = page.url();
});

test('a stranger with no account plays the shared link through to a full score', async ({ browser }) => {
  // A fresh, cookie-free context is a fresh visitor as far as the server is concerned — the same trick
  // vocabulary.spec.ts uses for the transfer-code test, and the only way to reach the guest detour for real.
  const guestContext = await browser.newContext();
  const guestPage = await guestContext.newPage();
  await guestPage.goto(gameUrl);

  // The quiz itself renders with no session at all — GameEndpoints.get mints nobody.
  await expect(guestPage.getByRole('button', { name: 'Start' })).toBeVisible();
  await guestPage.getByRole('button', { name: 'Start' }).click();

  // Starting a play is the first write, so it is what mints the guest account here.
  await expect(guestPage.getByRole('heading', { name: 'Your words are saved on this device' })).toBeVisible();

  for (let i = 0; i < words.length; i++) {
    // Scoped to the prompt's own heading class: the guest banner above it is an `h2` too ("Your words are saved on
    // this device"), and a bare `h2` locator matches both once the guest account exists.
    const heading = guestPage.locator('h2.text-xl');
    const promptText = (await heading.textContent())?.trim() ?? '';
    const match = words.find((w) => w.term === promptText);
    expect(match, `unexpected quiz prompt: "${promptText}"`).toBeTruthy();

    await guestPage.getByPlaceholder('Type the translation').fill(match!.hu);
    await guestPage.getByRole('button', { name: 'Submit' }).click();

    if (i < words.length - 1) {
      // Prompts arrive in a random order (GameService.nextPrompt picks one at random from what is left), so the
      // only thing worth waiting for is that this word is no longer the one shown — not which word replaces it.
      await expect(heading).not.toHaveText(promptText);
    } else {
      // The last correct answer ends the play; the prompt card is replaced by the results screen.
      await expect(guestPage.getByText('Quiz complete')).toBeVisible();
    }
  }

  // Every word answered exactly right: maxPointsPerWord (2) per word, three words.
  await expect(guestPage.getByText(`Score: ${words.length * 2} / ${words.length * 2}`)).toBeVisible();
  await expect(guestPage.locator('table tbody tr')).toHaveCount(words.length);
  await expect(guestPage.getByText('Correct')).toHaveCount(words.length);

  await guestContext.close();
});
