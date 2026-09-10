import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';

// Exporting a tag to a JSON file and importing it into another account — the cross-instance form of "copy tag".
//
// Requires the real stack (see playwright.config.ts) *and* the dictionary sample loaded:
//
//   sbt "backend/runMain gathedge.backend.tools.DictionaryImport --seed"
//
// `der Mann` and its `ember` chip are the same pair vocabulary.spec.ts relies on.

const unique = Date.now();
const password = 'password123';

test.describe.configure({ mode: 'serial' });

let exported = '';

const wordRow = (p: Page, headword: string) =>
  p.locator('tr').filter({ has: p.locator('a.link.font-medium', { hasText: new RegExp(`^${headword}$`) }) });

async function signUp(page: Page, email: string) {
  await page.goto('/en/sign-up');
  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page).toHaveURL(/\/en\/$/);
}

// Tag creation moved off the Words page collect bar to the Tags editor. Mint a tag there, name it, and hand
// back its id so the caller can pick it in the "Collect into" select (the option value is the tag id).
async function createTag(page: Page, name: string): Promise<string> {
  await page.goto('/en/tags/new');
  await expect(page).toHaveURL(/\/en\/tags\/\d+$/);
  const id = page.url().match(/\/tags\/(\d+)/)![1];
  // Let the editor finish mounting before touching the rename control — clicking it mid-mint drops the input.
  await expect(page.getByRole('heading', { name: 'Add a word pair' })).toBeVisible();
  await page.getByRole('button', { name: 'Rename wordlist' }).click();
  const box = page.getByRole('textbox', { name: 'New name' });
  await box.fill(name);
  await expect(box).toHaveValue(name);
  const renamed = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && new RegExp(`/api/tags/${id}(\\?|$)`).test(r.url()),
  );
  await box.press('Enter');
  await renamed;
  // Re-read from the server, not the still-open edit form whose input value trips a heading-name match.
  await page.goto(`/en/tags/${id}`);
  await expect(page.locator('h1')).toContainText(name);
  return id;
}

test('an account builds a tag and exports it to a file', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signUp(page, `e2e-tag-export-${unique}@example.com`);

  const tagId = await createTag(page, `xfer${unique}`);
  await page.goto('/en/words');
  await page.getByLabel('Collect into').selectOption(tagId);
  await expect(page.getByLabel('Collect into').locator('option:checked')).toHaveText(new RegExp(`xfer${unique}`));

  await page.locator('input[type=search]').fill('mann');
  await wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ }).click();
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
  await wordRow(page, 'der Mann').getByRole('button', { name: /^ember / }).click();
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /^ember / })).toHaveAttribute('aria-pressed', 'true');

  // The per-tag "Export" button on the tag details page — "Export all tags" on `/en/tags` is the account-wide form.
  await page.goto(`/en/tags/${tagId}`);
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export', exact: true }).click();
  const download = await downloadPromise;
  exported = fs.readFileSync(await download.path(), 'utf8');

  const parsed = JSON.parse(exported);
  expect(parsed.version).toBe(2);
  expect(parsed.tags[0].name).toBe(`xfer${unique}`);
  expect(parsed.tags.flatMap((t: any) => t.entries).some((e: any) => e.marked.length > 0)).toBe(true);

  await context.close();
});

test('another account imports that file and gets the tag, its word and its mark', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signUp(page, `e2e-tag-import-${unique}@example.com`);

  await page.goto('/en/tags');
  await page.getByRole('button', { name: 'Import' }).click();

  // Scoped by the dialog's own heading: the shell also mounts a hidden guest "sign in" confirm
  // `.modal-box`, and its copy ("…leaves this device's words behind…") would otherwise collide
  // with a bare `/word/` match here.
  const modal = page.locator('.modal-box').filter({ hasText: 'Import wordlists' });
  await modal.locator('input[type=file]').setInputFiles({
    name: 'tags.json',
    mimeType: 'application/json',
    buffer: Buffer.from(exported),
  });
  await expect(modal.getByText(/\d+ marked translations?/)).toBeVisible();
  await modal.getByRole('button', { name: 'Import' }).click();
  await expect(modal.getByText(new RegExp(`xfer${unique}.*created`))).toBeVisible();
  await modal.getByRole('button', { name: 'Done' }).click();

  const tagLink = page.getByRole('link', { name: `xfer${unique}` });
  await expect(tagLink).toBeVisible();
  // The tag id, read off its own detail link. Tags are world-visible, so the export test's own same-named
  // tag is in the collect select too — this account's imported copy has to be picked by id, not by name.
  const importedTagId = (await tagLink.getAttribute('href'))?.match(/\/tags\/(\d+)/)?.[1];
  expect(importedTagId).toBeTruthy();

  // Make the imported tag the collect tag: the tick and the chip on the words page read the collect tag,
  // so this is how "the word and its mark came across" shows on that screen.
  await page.goto('/en/words?q=mann');
  await page.getByLabel('Collect into').selectOption(importedTagId!);
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
  await expect(wordRow(page, 'der Mann').getByRole('button', { name: /^ember / })).toHaveAttribute('aria-pressed', 'true');

  await context.close();
});
