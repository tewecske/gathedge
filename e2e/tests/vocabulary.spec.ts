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
  await expect(page.getByText('Your words are saved on this device')).toHaveCount(0);
});

test('tagging a word mints a guest account and keeps the word', async () => {
  await page.locator('tr', { hasText: 'das Haus' }).getByRole('button').click();

  // The banner is the first thing that tells the visitor they now have an account.
  await expect(page.getByText('Your words are saved on this device')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Get a transfer code' })).toBeVisible();

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
  await expect(page.getByText('Your words are saved on this device')).toHaveCount(0);

  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/en\/sign-in$/);

  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();

  await page.goto('/en/words?q=hau');
  await expect(page.locator('tr', { hasText: 'das Haus' }).getByRole('button')).toContainText('✓');
});

test('a tag files words under a name, and the listing can be narrowed to it', async () => {
  await page.goto('/en/words');
  await page.locator('input[placeholder="lesson1"]').fill('lesson1');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  // Creating a tag files under it immediately: it is made in order to be used.
  await expect(page).toHaveURL(/[?&]tag=\d+/);

  await page.locator('input[type=search]').fill('brot');
  await page.locator('tr', { hasText: 'das Brot' }).getByRole('button').click();
  await expect(page.locator('tr', { hasText: 'das Brot' }).getByRole('button')).toContainText('✓');

  // The whole listing state is in the address, so this is a link somebody could have been sent.
  const tagged = page.url().replace(/[?&]q=brot/, '');
  await page.goto(tagged);
  await page.getByText('Only my words').click();
  await expect(page.locator('tr', { hasText: 'das Brot' })).toBeVisible();
});

test('a word the dictionary does not have can be added, with its article', async () => {
  await page.goto('/en/words');
  await page.locator('input[type=search]').fill('Zwetschge');
  await expect(page.getByText('Add “Zwetschge”')).toBeVisible();

  await page.locator('.card', { hasText: 'Add “Zwetschge”' }).locator('input.input').fill('szilva');
  await page.locator('.card', { hasText: 'Add “Zwetschge”' }).getByRole('button', { name: 'Add' }).click();

  // Straight to the word: whatever anybody else already recorded about it is on that screen.
  await expect(page).toHaveURL(/\/en\/words\/\d+$/);
  await expect(page.getByText('szilva')).toBeVisible();
  await expect(page.getByText('added by a user')).toBeVisible();
});
