import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';

// Exporting a tag to a JSON file and importing it into another account — the cross-instance form of "copy tag".
//
// Requires the real stack (see playwright.config.ts) *and* the dictionary sample loaded:
//
//   sbt "backend/runMain gathedge.backend.tools.DictionaryImport --seed"
//
// `das Haus` and its `ház` chip are the same pair vocabulary.spec.ts relies on.

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

test('an account builds a tag and exports it to a file', async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signUp(page, `e2e-tag-export-${unique}@example.com`);

  await page.goto('/en/words');
  await page.locator('input[placeholder="lesson1"]').fill(`xfer${unique}`);
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByLabel('Collect into').locator('option:checked')).toHaveText(new RegExp(`^xfer${unique}`));

  await page.locator('input[type=search]').fill('hau');
  await wordRow(page, 'das Haus').getByRole('button', { name: /my vocabulary/ }).click();
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
  await wordRow(page, 'das Haus').getByRole('button', { name: /^ház / }).click();
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /^ház / })).toHaveAttribute('aria-pressed', 'true');

  await page.goto('/en/tags');
  await page.getByRole('link', { name: `xfer${unique}` }).click();

  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export' }).click();
  const download = await downloadPromise;
  exported = fs.readFileSync(await download.path(), 'utf8');

  const parsed = JSON.parse(exported);
  expect(parsed.version).toBe(1);
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

  const modal = page.locator('.modal-box');
  await modal.locator('input[type=file]').setInputFiles({
    name: 'tags.json',
    mimeType: 'application/json',
    buffer: Buffer.from(exported),
  });
  await expect(modal.getByText(/word/)).toBeVisible();
  await modal.getByRole('button', { name: 'Import' }).click();
  await expect(modal.getByText(new RegExp(`xfer${unique}.*created`))).toBeVisible();
  await modal.getByRole('button', { name: 'Done' }).click();

  await expect(page.getByRole('link', { name: `xfer${unique}` })).toBeVisible();

  await page.goto(`/en/words?q=hau&tag=`);
  await page.getByLabel('Filter by tag').selectOption({ label: `xfer${unique}` });
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /my vocabulary/ })).toContainText('✓');
  await expect(wordRow(page, 'das Haus').getByRole('button', { name: /^ház / })).toHaveAttribute('aria-pressed', 'true');

  await context.close();
});
