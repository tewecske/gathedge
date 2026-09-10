import { test, expect, type Page } from '@playwright/test';

// The vocabulary, walked the way its first user does: with no account at all.
//
// Requires the real stack (see playwright.config.ts) *and* the dictionary sample loaded:
//
//   sbt "backend/runMain gathedge.backend.tools.DictionaryImport --seed"
//
// Without it every search matches nothing and the tagging tests have no row to click. The words
// used below ("der Mann" / "ember" and the plural form "Männer") are common enough to be in any
// `data/dictionary/seed.tsv` build.
//
// What this covers that no other suite can: the guest account. It is minted by the browser on
// the first tag, carried to a second browser context by a transfer code, and turned into a real
// account — three things that only exist as a sequence of real requests with a real cookie jar.

const unique = Date.now();
const password = 'password123';

test.describe.configure({ mode: 'serial' });

let page: Page;
let transferCode: string;

// A result row picked by its headword-cell link (`a.link.font-medium`), matched whole. Rows for inflected forms
// (Manne, Mannes, …) plus the lemma back-link inside each form's own row all repeat the lemma's text, so a
// plain `locator('tr', { hasText })` matches several rows once a search pulls in a word's whole family.
const wordRow = (p: Page, headword: string) =>
  p.locator('tr').filter({ has: p.locator('a.link.font-medium', { hasText: new RegExp(`^${headword}$`) }) });

// Tag creation moved off the Words page collect bar to the Tags editor. Mint a tag there, name it, and hand back
// its id so the caller can pick it in the "Collect into" select (the select's option value is the tag id).
async function createTag(p: Page, name: string): Promise<string> {
  await p.goto('/en/tags/new');
  await expect(p).toHaveURL(/\/en\/tags\/\d+$/);
  const id = p.url().match(/\/tags\/(\d+)/)![1];
  // Let the editor finish mounting before touching the rename control — clicking it mid-mint drops the input.
  await expect(p.getByRole('heading', { name: 'Add a word pair' })).toBeVisible();
  await p.getByRole('button', { name: 'Rename wordlist' }).click();
  const box = p.getByRole('textbox', { name: 'New name' });
  await box.fill(name);
  await expect(box).toHaveValue(name);
  const renamed = p.waitForResponse(
    (r) => r.request().method() === 'PUT' && new RegExp(`/api/tags/${id}(\\?|$)`).test(r.url()),
  );
  await box.press('Enter');
  await renamed;
  // Re-read from the server, not the still-open edit form whose input value trips a heading-name match.
  await p.goto(`/en/tags/${id}`);
  await expect(p.locator('h1')).toContainText(name);
  return id;
}

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
});

test.afterAll(async () => {
  await page.close();
});

test('a visitor with no account can search the dictionary', async () => {
  await page.goto('/en/words');
  await expect(page.getByRole('heading', { name: 'Words' })).toBeVisible();

  await page.locator('input[type=search]').fill('mann');
  // Debounced at 300ms, then a round trip.
  await expect(page).toHaveURL(/[?&]q=mann/);
  // `wordRow`, not a bare text/link locator: other rows for forms of "Mann" (Manne, Mannes, Männer…) each carry a
  // plain lemma back-link reading "der Mann" too.
  const mannRow = wordRow(page, 'der Mann');
  await expect(mannRow).toBeVisible();
  // The German article is part of the word, and the Hungarian translation is on the row.
  await expect(mannRow).toContainText('ember');
});

test('searching a plural form shows it as its own row, with the lemma alongside for context', async () => {
  // "Männer" (plural of "Mann") never shares a prefix with "mann" (the umlaut breaks it), so this is the search
  // landing on the variant's own spelling directly, not a leftover match from the test above.
  await page.goto('/en/words?q=m%C3%A4nner');
  // `wordRow` again: the "der Mann" context row lists "Männer" in its variants cell, so a row filter on any
  // "Männer" link would match that row too.
  const variantRow = wordRow(page, 'Männer');
  await expect(variantRow).toBeVisible();
  // Variant type column, in the language this row was searched in.
  await expect(variantRow).toContainText('plural');
  const lemmaRow = wordRow(page, 'der Mann');
  await expect(lemmaRow).toBeVisible();
  await expect(lemmaRow).toContainText('★');
});

test('the collect bar is there for a visitor with no account yet, but the tag filter is not', async () => {
  // The collect bar's hint is shown to everybody, so a first-time visitor sees where a tick files before their
  // first tick mints an account. The select itself waits for a session; the tag *filter*, the "only mine" filter
  // and the guest banner all still belong to an account.
  await expect(page.getByText('Words you tick go into this wordlist.')).toBeVisible();
  await expect(page.getByLabel('Collect into')).toHaveCount(0);
  await expect(page.getByText('Only my words')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'You have data saved as a guest' })).toHaveCount(0);
});

test('tagging a word mints a guest account and keeps the word', async () => {
  await wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ }).click();

  // The banner is the first thing that tells the visitor they now have an account.
  // By role: the account menu offers the same words as a link to the banner, so plain text matches twice.
  await expect(page.getByRole('heading', { name: 'You have data saved as a guest' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Get a transfer code' })).toBeVisible();

  // The banner appears as soon as the guest exists, which is two requests before the word is actually
  // filed — reloading on the banner alone cancels the tag write in flight. The tick is the signal that
  // the write landed.
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');

  await page.reload();
  await page.locator('input[type=search]').fill('mann');
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
});

test('a transfer code is shown once and carries the vocabulary to another browser', async ({ browser }) => {
  await page.getByRole('button', { name: 'Get a transfer code' }).click();
  const code = page.locator('code');
  await expect(code).toBeVisible();
  transferCode = (await code.textContent()) ?? '';
  expect(transferCode).toMatch(/^[0-9A-Z]{4}(-[0-9A-Z]{4}){3}$/);

  // A second browser context is a second machine as far as cookies are concerned.
  const other = await browser.newContext();
  const elsewhere = await other.newPage();
  await elsewhere.goto('/en/sign-in');
  await elsewhere.getByRole('link', { name: 'Have a transfer code?' }).click();
  await elsewhere.locator('input[placeholder="XXXX-XXXX-XXXX-XXXX"]').fill(transferCode);
  await elsewhere.getByRole('button', { name: 'Continue' }).click();

  await expect(elsewhere).toHaveURL(/\/en\/words/);
  await elsewhere.locator('input[type=search]').fill('mann');
  await expect(wordRow(elsewhere, 'der Mann').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
  await other.close();
});

test('upgrading keeps every word, and the account can sign in afterwards', async () => {
  const email = `e2e-guest-${unique}@example.com`;

  // The shell banner's upgrade control is a real `<a href>` (it navigates to Page.SignUp), so its accessible
  // role is "link" even though it is styled as a button.
  await page.getByRole('link', { name: 'Create an account' }).click();
  await expect(page.getByRole('heading', { name: 'Create account' })).toBeVisible();
  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  // The submit button reads "Sign up" even here — only the heading and hint change for a guest upgrade
  // (SignUpPage.isGuestSignedIn); the action itself is still phrased the same as a plain signup.
  await page.getByRole('button', { name: 'Sign up' }).click();

  // An upgraded guest is signed in and no longer a guest, so `RequireAnon` fires the same redirect signing up or
  // signing in does — off the sign-up page, to Games (App.redirectTarget; SignUpPage's own doc comment on why it does
  // not navigate itself). Waiting for that page to land, rather than clicking straight through, is what keeps the
  // clicks below off the moment the shell is still being torn down and rebuilt underneath them.
  await expect(page).toHaveURL(/\/en\/$/);

  // The banner belongs to guests, so it goes as soon as the account is a real one.
  await expect(page.getByRole('heading', { name: 'You have data saved as a guest' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/en\/sign-in$/);

  await page.locator('input[name=identifier]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  // Wait for the sign-in to land: navigating while the request is in flight cancels it, and the page
  // that follows is then an anonymous one whose rows carry no tags.
  await page.waitForURL(/\/en\/$/);

  await page.goto('/en/words?q=mann');
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
});

// The collect select says where a tick files, nothing more: picking a tag there does not narrow the
// listing and does not reach the address. The word it files really lands under that named tag.
test('the collect select files ticks under a named tag, without touching the listing', async () => {
  const tagId = await createTag(page, 'lesson1');

  await page.goto('/en/words');
  const collect = page.getByLabel('Collect into');
  await collect.selectOption(tagId);
  await expect(collect.locator('option:checked')).toHaveText(/lesson1/);
  // Choosing a collect tag is not a listing filter — it stays out of the URL.
  await expect(page).not.toHaveURL(/[?&]tag=/);

  await page.locator('input[type=search]').fill('mann');
  await wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ }).click();
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');

  // The tick really landed under "lesson1": the tag's own editor lists the word.
  await page.goto(`/en/tags/${tagId}`);
  await expect(page.locator('tbody tr').filter({ hasText: 'Mann' })).toBeVisible();
});

// A chip is the second thing a click on this row can do: the tick says "I am learning this word", a chip
// says "and this is the answer I want to be asked for". The chip files the translation as a word of its
// own as well, which is what makes the pair answerable from either side.
test('clicking a translation marks it as a practice answer, and files the translation too', async () => {
  // Arrive with the search already in the URL, not a bare `/en/words` then a typed query: "remember
  // filters" can restore an "Only my words" state from an earlier test, and a bare arrival would then
  // hide every row not in the collect tag before the search narrows things.
  await page.goto('/en/words?q=mann');
  const chip = wordRow(page, 'der Mann').getByRole('button', { name: /^ember / });

  await expect(chip).toHaveAttribute('aria-pressed', 'false');
  await chip.click();
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /^ember / })).toHaveAttribute(
    'aria-pressed',
    'true',
  );

  await page.reload();
  await page.locator('input[type=search]').fill('mann');
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /^ember / })).toHaveAttribute(
    'aria-pressed',
    'true',
  );

  // The Hungarian side is now in the vocabulary as well, which no tick put there. `mine=true` and a headword-
  // scoped row: search folds accents and pulls in the "ember" family (embert, embernek, …), so `wordRow`
  // matches the lemma alone rather than any row mentioning it.
  await page.goto('/en/words?lang=hu&target=de&q=ember&mine=true');
  await expect(
    wordRow(page, 'ember').getByRole('button', { name: /my vocabulary/ }),
  ).toContainText('✓');
});

// The word is unique per run because `words` is shared by every account: a fixed one would exist by the
// second run of this suite, the search would find it, and the "add a word" form it needs would be absent.
const newWord = `Zwetschge${unique}`;

test('a word the dictionary does not have can be added, with its article', async () => {
  await page.goto('/en/words');
  await page.locator('input[type=search]').fill(newWord);
  await expect(page.getByText(`Add “${newWord}”`)).toBeVisible();

  // A box per other language, not one for whichever the listing happens to show. Only one is filled here,
  // so the next test has a language that is genuinely still missing.
  const form = page.locator('.card', { hasText: `Add “${newWord}”` });
  await expect(form.getByLabel('English')).toBeVisible();
  await form.getByLabel('Hungarian').fill('szilva');
  await form.getByRole('button', { name: 'Add' }).click();

  // Straight to the word: whatever anybody else already recorded about it is on that screen.
  await expect(page).toHaveURL(/\/en\/words\/\d+$/);
  await expect(page.getByText('szilva')).toBeVisible();
  await expect(page.getByText('added by a user').first()).toBeVisible();
});

// The detail page is the only place a word gains a translation in a language the listing was not showing,
// so it is the one that has to be findable: a named form, and the missing language shown as missing.
test('the detail page adds a translation in the language still missing', async () => {
  // One group per language the word is not, each shown even when empty. Scope by the group's own
  // language badge: with three other languages, "No translations yet" stands in more than one group.
  const englishGroup = page.locator('.badge', { hasText: 'English' }).locator('..');
  // The word from the previous test, still open.
  await expect(englishGroup.getByText('No translations yet')).toBeVisible();

  const form = page.locator('form', { hasText: 'Translation' });
  await form.getByLabel('Translation language').selectOption('en');
  await form.getByLabel('Translation', { exact: true }).fill('plum');
  await form.getByRole('button', { name: 'Add' }).click();

  await expect(page.getByText('plum')).toBeVisible();
  await expect(englishGroup.getByText('No translations yet')).toHaveCount(0);
  // And the form is still there, on the same word, for whatever is added next.
  await expect(page.getByRole('heading', { name: 'Add a translation' })).toBeVisible();
});

// The same two actions the listing offers, on the screen that shows every language at once. The chip that
// marks a translation as the answer files into the collect tag, so the pair it marks has to fit that tag's
// language pair — here the collect tag is `de → hu`, so the Hungarian translation is the one it can mark.
// One collect tag stands behind both screens, so a tick here is a tick there.
test('the detail page collects the word and marks a translation', async () => {
  // Still on the word from the previous test. It was added through the listing's form, so it arrived filed
  // under the collect tag: the tick here answers the same question the row's does.
  await expect(page.getByRole('button', { name: /my vocabulary/ })).toContainText('✓');

  // Adding the word through the listing form with a Hungarian translation already marked that pair, so the
  // chip starts pressed. Unmark it and mark it again — the chip is a control here, not a read-out.
  const chip = page.getByRole('button', { name: /^szilva/ });
  await expect(chip).toHaveAttribute('aria-pressed', 'true');
  await chip.click();
  await expect(page.getByRole('button', { name: /^szilva/ })).toHaveAttribute('aria-pressed', 'false');
  await page.getByRole('button', { name: /^szilva/ }).click();
  await expect(page.getByRole('button', { name: /^szilva/ })).toHaveAttribute('aria-pressed', 'true');

  await page.reload();
  await expect(page.getByRole('button', { name: /^szilva/ })).toHaveAttribute('aria-pressed', 'true');

  // Out of the vocabulary and back in: the tick is a control here, not a read-out of one.
  await page.getByRole('button', { name: /my vocabulary/ }).click();
  await expect(page.getByRole('button', { name: /my vocabulary/ })).toContainText('+');
  await page.getByRole('button', { name: /my vocabulary/ }).click();
  await expect(page.getByRole('button', { name: /my vocabulary/ })).toContainText('✓');

  // And the listing agrees, because there is one collect tag and not one per screen.
  await page.goto(`/en/words?lang=de&target=en&q=${newWord}`);
  await expect(
    page.locator('tr', { hasText: newWord }).getByRole('button', { name: /my vocabulary/ }),
  ).toContainText('✓');
});
