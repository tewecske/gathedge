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
  await expect(page.getByRole('link', { name: 'das Haus' })).toBeVisible();
  // The German article is part of the word, and the Hungarian translation is on the row.
  await expect(page.locator('tr', { hasText: 'das Haus' })).toContainText('ház');
});

test('the tag controls are absent until there is somebody to own a tag', async () => {
  await expect(page.getByText('Only my words')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Your words are saved on this device' })).toHaveCount(0);
});

test('tagging a word mints a guest account and keeps the word', async () => {
  await page.locator('tr', { hasText: 'das Haus' }).getByRole('button').click();

  // The banner is the first thing that tells the visitor they now have an account.
  // By role: the account menu offers the same words as a link to the banner, so plain text matches twice.
  await expect(page.getByRole('heading', { name: 'Your words are saved on this device' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Get a transfer code' })).toBeVisible();

  // The banner appears as soon as the guest exists, which is two requests before the word is actually
  // filed — reloading on the banner alone cancels the tag write in flight. The tick is the signal that
  // the write landed.
  await expect(page.locator('tr', { hasText: 'das Haus' }).getByRole('button')).toContainText('✓');

  await page.reload();
  await page.locator('input[type=search]').fill('hau');
  await expect(page.locator('tr', { hasText: 'das Haus' }).getByRole('button')).toContainText('✓');
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
  await expect(elsewhere.locator('tr', { hasText: 'das Haus' }).getByRole('button')).toContainText('✓');
  await other.close();
});

test('upgrading keeps every word, and the account can sign in afterwards', async () => {
  const email = `e2e-guest-${unique}@example.com`;

  await page.getByRole('button', { name: 'Create an account' }).click();
  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Create account' }).click();

  // The banner belongs to guests, so it goes as soon as the account is a real one.
  await expect(page.getByRole('heading', { name: 'Your words are saved on this device' })).toHaveCount(0);

  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/en\/sign-in$/);

  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  // Wait for the sign-in to land: navigating while the request is in flight cancels it, and the page
  // that follows is then an anonymous one whose rows carry no tags.
  await page.waitForURL(/\/en\/$/);

  await page.goto('/en/words?q=hau');
  await expect(page.locator('tr', { hasText: 'das Haus' }).getByRole('button')).toContainText('✓');
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
  await expect(page.getByLabel('Collect into')).toHaveValue(/\d+/);
  await expect(page).not.toHaveURL(/[?&]tag=\d+/);
  const tagId = await page.getByLabel('Collect into').inputValue();

  await page.locator('input[type=search]').fill('brot');
  await page.locator('tr', { hasText: 'das Brot' }).getByRole('button').click();
  await expect(page.locator('tr', { hasText: 'das Brot' }).getByRole('button')).toContainText('✓');

  // Narrowing to the tag is the other control, and it is the half that reaches the URL.
  await page.locator('input[type=search]').fill('');
  // The search is debounced, so wait for it to leave the address before changing the other control:
  // two listing writes in flight at once is a race, not a thing a reader can do.
  await expect(page).not.toHaveURL(/[?&]q=/);
  await page.getByLabel('Filter by tag').selectOption(tagId);
  await expect(page).toHaveURL(/[?&]tag=\d+/);
  await expect(page.locator('tr', { hasText: 'das Brot' })).toBeVisible();

  // The whole listing state is in the address, so this is a link somebody could have been sent.
  await page.goto(page.url());
  await page.getByText('Only my words').click();
  await expect(page.locator('tr', { hasText: 'das Brot' })).toBeVisible();
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
  // The word from the previous test, still open.
  await expect(page.getByText('No translations yet')).toBeVisible();

  const form = page.locator('form', { hasText: 'Translation' });
  await form.getByLabel('Translation language').selectOption('en');
  await form.getByLabel('Translation', { exact: true }).fill('plum');
  await form.getByRole('button', { name: 'Add' }).click();

  await expect(page.getByText('plum')).toBeVisible();
  await expect(page.getByText('No translations yet')).toHaveCount(0);
  // And the form is still there, on the same word, for whatever is added next.
  await expect(page.getByRole('heading', { name: 'Add a translation' })).toBeVisible();
});
