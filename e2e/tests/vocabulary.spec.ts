import { test, expect, type Page } from '@playwright/test';

// The vocabulary, walked the way its first user does: with no account at all.
//
// Requires the real stack (see playwright.config.ts) *and* the dictionary sample loaded:
//
//   sbt "backend/runMain gathedge.backend.tools.DictionaryImport --seed"
//
// Without it every search matches nothing and the tagging tests have no row to click. The
// words used below are in `data/dictionary/seed.tsv`.
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
// (das Hauserl, Häusern, …) plus the lemma back-link inside each form's own row all repeat the lemma's text, so a
// plain `locator('tr', { hasText })` matches several rows once a search pulls in a word's whole family.
const wordRow = (p: Page, headword: string) =>
  p.locator('tr').filter({ has: p.locator('a.link.font-medium', { hasText: new RegExp(`^${headword}$`) }) });

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
});

test.afterAll(async () => {
  await page.close();
});

test('a visitor with no account can search the dictionary', async () => {
  await page.goto('/en/words');
  await expect(page.getByRole('heading', { name: 'Words' })).toBeVisible();

  await page.locator('input[type=search]').fill('hau');
  // Debounced at 300ms, then a round trip.
  await expect(page).toHaveURL(/[?&]q=hau/);
  // `wordRow`, not a bare text/link locator: other rows for forms of "Haus" (Hause, Hauses, haust…) each carry a
  // plain lemma back-link reading "das Haus" too.
  const hausRow = wordRow(page, 'das Haus');
  await expect(hausRow).toBeVisible();
  // The German article is part of the word, and the Hungarian translation is on the row.
  await expect(hausRow).toContainText('ház');
});

test('searching a plural form shows it as its own row, with the lemma alongside for context', async () => {
  // "Häuser" (plural of "Haus") never shares a prefix with "haus" (the umlaut breaks it), so this is the search
  // landing on the variant's own spelling directly, not a leftover match from the test above.
  await page.goto('/en/words?q=h%C3%A4user');
  // `wordRow` again: the "das Haus" context row lists "Häuser" in its variants cell, so a row filter on any
  // "Häuser" link would match that row too.
  const variantRow = wordRow(page, 'Häuser');
  await expect(variantRow).toBeVisible();
  // Variant type column, in the language this row was searched in.
  await expect(variantRow).toContainText('plural');
  const lemmaRow = wordRow(page, 'das Haus');
  await expect(lemmaRow).toBeVisible();
  await expect(lemmaRow).toContainText('★');
});

test('the collect bar is there for a visitor with no account yet, but the tag filter is not', async () => {
  // Where a tick files, and the way to make a tag: shown to everybody, so a first-time visitor can see and use it
  // before their first tick mints an account. The tag *filter* and the guest banner still belong to an account.
  await expect(page.locator('input[placeholder="lesson1"]')).toBeVisible();
  await expect(page.getByText('Only my words')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'You have data saved as a guest' })).toHaveCount(0);
});

test('tagging a word mints a guest account and keeps the word', async () => {
  await wordRow(page, 'das Haus').getByRole('button', { name: /my vocabulary/ }).click();

  // The banner is the first thing that tells the visitor they now have an account.
  // By role: the account menu offers the same words as a link to the banner, so plain text matches twice.
  await expect(page.getByRole('heading', { name: 'You have data saved as a guest' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Get a transfer code' })).toBeVisible();

  // The banner appears as soon as the guest exists, which is two requests before the word is actually
  // filed — reloading on the banner alone cancels the tag write in flight. The tick is the signal that
  // the write landed.
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');

  await page.reload();
  await page.locator('input[type=search]').fill('hau');
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
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
  await elsewhere.locator('input[type=search]').fill('hau');
  await expect(wordRow(elsewhere, 'das Haus').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
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

  await page.goto('/en/words?q=hau');
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
});

// The two tag controls are deliberately different things, and this is the test that says so: creating a
// tag changes where ticks are filed and *not* what the listing shows, and the filter changes what the
// listing shows and not where ticks go. They were one select, which is what made both unusable.
test('a tag files words under a name, and the filter is a separate control', async () => {
  await page.goto('/en/words');
  await page.locator('input[placeholder="lesson1"]').fill('lesson1');
  await page.getByRole('button', { name: 'Add', exact: true }).click();

  // Creating a tag collects into it immediately — it is made in order to be used — but the address, which
  // is where the *filter* lives, is untouched.
  // By name, not merely "some id": the account already had a tag, so any id would pass while the select
  // still showed the old one — and the rest of this test would then be about that tag instead.
  const collect = page.getByLabel('Collect into');
  await expect(collect.locator('option:checked')).toHaveText(/^lesson1/);
  await expect(page).not.toHaveURL(/[?&]tag=\d+/);
  const tagId = await collect.inputValue();

  await page.locator('input[type=search]').fill('brot');
  await wordRow(page, 'das Brot').getByRole('button', { name: /my vocabulary/ }).click();
  await expect(wordRow(page, 'das Brot').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');

  // Narrowing to the tag is the other control, and it is the half that reaches the URL.
  await page.locator('input[type=search]').fill('');
  // The search is debounced, so wait for it to leave the address before changing the other control:
  // two listing writes in flight at once is a race, not a thing a reader can do.
  await expect(page).not.toHaveURL(/[?&]q=/);
  await page.getByLabel('Filter by tag').selectOption(tagId);
  await expect(page).toHaveURL(/[?&]tag=\d+/);
  await expect(wordRow(page, 'das Brot')).toBeVisible();

  // The whole listing state is in the address, so this is a link somebody could have been sent.
  await page.goto(page.url());
  // Wait for the page to have read the address before touching another control: a write made while the
  // query is still the default one is made *against* the default, and takes the filter out with it.
  await expect(page.getByLabel('Filter by tag')).toHaveValue(tagId);
  await page.getByText('Only my words').click();
  await expect(wordRow(page, 'das Brot')).toBeVisible();
});

// A chip is the second thing a click on this row can do: the tick says "I am learning this word", a chip
// says "and this is the answer I want to be asked for". The chip files the translation as a word of its
// own as well, which is what makes the pair answerable from either side.
test('clicking a translation marks it as a practice answer, and files the translation too', async () => {
  // Arrive with the search already in the URL, not a bare `/en/words` then a typed query: since "remember
  // filters", a bare arrival restores the "Only my words" filter the tag test left on, which hides every row
  // that is not in that account's collect tag — "das Haus" included.
  await page.goto('/en/words?q=hau');
  const chip = wordRow(page, 'das Haus').getByRole('button', { name: /^ház / });

  await expect(chip).toHaveAttribute('aria-pressed', 'false');
  await chip.click();
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /^ház / })).toHaveAttribute(
    'aria-pressed',
    'true',
  );

  await page.reload();
  await page.locator('input[type=search]').fill('hau');
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /^ház / })).toHaveAttribute(
    'aria-pressed',
    'true',
  );

  // The Hungarian side is now in the vocabulary as well, which no tick put there. `mine=true` and a headword-
  // scoped row: search folds accents, so a bare `q=ház` also pulls in the whole "haza" family (44 forms), and
  // the freshly filed "ház" — a large frequency rank — sorts below the first result page among them.
  await page.goto('/en/words?lang=hu&target=de&q=ház&mine=true');
  await expect(
    wordRow(page, 'ház').getByRole('button', { name: /my vocabulary/ }),
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

// The same two actions the listing offers, on the screen that shows every language at once — which is the
// only place a translation outside the listing's target language can be marked at all. One collect tag
// stands behind both screens, so a tick here is a tick there.
test('the detail page collects the word and marks a translation', async () => {
  // Still on the word from the previous test. It was added through the listing's form, so it arrived filed
  // under the collect tag: the tick here answers the same question the row's does.
  await expect(page.getByRole('button', { name: /my vocabulary/ })).toContainText('✓');

  const chip = page.getByRole('button', { name: /^plum/ });
  await expect(chip).toHaveAttribute('aria-pressed', 'false');
  await chip.click();
  await expect(page.getByRole('button', { name: /^plum/ })).toHaveAttribute('aria-pressed', 'true');

  await page.reload();
  await expect(page.getByRole('button', { name: /^plum/ })).toHaveAttribute('aria-pressed', 'true');

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
