import { test, expect, type Page } from '@playwright/test';

// The unified tag editor (`/tags/new` -> mint -> `/tags/{id}`): add a pair, edit it in place, remove it.
//
// Requires the real stack (see playwright.config.ts). No dictionary seed needed — every word here is typed and
// created on the spot, which is the "+ new" path of the autocomplete.
//
// Regressions this locks down, all found by hand:
//   - pressing Enter in the edit row's target field saves the change and leaves edit mode (it used to only close
//     the autocomplete and do nothing);
//   - "Remove pair" deletes the row instead of surfacing an error;
//   - a source word with two marked translations loses only the row whose "Remove pair" was clicked (it used to
//     take both, because the delete was addressed by the source word alone).

test.describe.configure({ mode: 'serial' });

const unique = Date.now();

let page: Page;

const addSourceInput = () =>
  page.locator('input[placeholder="Type a German word"]').first();
const addTargetInput = () =>
  page.locator('input[placeholder="Type a Hungarian word"]').first();

const rowFor = (text: string) => page.locator('tbody tr').filter({ hasText: text });

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
  // Mint a guest so the write endpoints have a session; the editor's mint-on-arrival needs one already.
  const res = await page.request.post('/api/guest', {
    headers: { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' },
    data: { theme: 'Light' },
  });
  expect(res.status()).toBe(201);
});

test.afterAll(async () => {
  await page.close();
});

test('arriving at /tags/new mints an Untitled tag and lands on its editor', async () => {
  await page.goto('/en/tags/new');
  await expect(page).toHaveURL(/\/en\/tags\/\d+$/);
  await expect(page.getByText('No words yet.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Add a word pair' })).toBeVisible();
});

test('a pair typed into the add row is saved immediately as a row', async () => {
  const src = `Editquelle${unique}`;
  const tgt = `editforras${unique}`;

  await addSourceInput().fill(src);
  await addSourceInput().press('Enter');
  await addTargetInput().fill(tgt);
  await addTargetInput().press('Enter');

  await expect(rowFor(src)).toBeVisible();
  await expect(rowFor(src)).toContainText(tgt);

  // Survives a reload — it really was persisted, not just held in the page.
  await page.reload();
  await expect(rowFor(src)).toContainText(tgt);
});

test('editing the target and pressing Enter saves it and leaves edit mode', async () => {
  const src = `Editquelle${unique}`;
  const newTgt = `editujforras${unique}`;

  await rowFor(src).getByRole('button', { name: 'Edit' }).click();

  // In edit mode the row shows a Save button and two pickers.
  await expect(rowFor(src).getByRole('button', { name: 'Save' })).toBeVisible();

  const editTarget = rowFor(src).locator('input[placeholder="Type a Hungarian word"]');
  await editTarget.fill(newTgt);
  await editTarget.press('Enter');

  // Left edit mode: Save gone, Edit back.
  await expect(rowFor(src).getByRole('button', { name: 'Save' })).toHaveCount(0);
  await expect(rowFor(src).getByRole('button', { name: 'Edit' })).toBeVisible();
  // And it actually changed.
  await expect(rowFor(src)).toContainText(newTgt);

  await page.reload();
  await expect(rowFor(src)).toContainText(newTgt);
});

test('Remove pair deletes the row and shows no error', async () => {
  const src = `Editquelle${unique}`;

  await rowFor(src).getByRole('button', { name: 'Remove pair' }).click();

  await expect(rowFor(src)).toHaveCount(0);
  await expect(page.locator('.alert-error')).toHaveCount(0);

  await page.reload();
  await expect(rowFor(src)).toHaveCount(0);
});

test('removing one translation of a word keeps the word\'s other translation', async () => {
  const src = `Multiquelle${unique}`;
  const tgtOne = `multiegy${unique}`;
  const tgtTwo = `multiketto${unique}`;

  const addPair = async (target: string) => {
    await addSourceInput().fill(src);
    await addSourceInput().press('Enter');
    await addTargetInput().fill(target);
    await addTargetInput().press('Enter');
    await expect(rowFor(target)).toBeVisible();
  };

  await addPair(tgtOne);
  await addPair(tgtTwo);
  await expect(page.locator('tbody tr').filter({ hasText: src })).toHaveCount(2);

  // Remove only the first translation's row.
  await rowFor(tgtOne).getByRole('button', { name: 'Remove pair' }).click();

  await expect(rowFor(tgtOne)).toHaveCount(0);
  await expect(rowFor(tgtTwo)).toBeVisible();
  await expect(page.locator('.alert-error')).toHaveCount(0);

  await page.reload();
  await expect(rowFor(tgtOne)).toHaveCount(0);
  await expect(rowFor(tgtTwo)).toContainText(src);
});

test('multiselect: Select all then Delete selected clears the visible rows in one go', async () => {
  const a = `Bulkdel${unique}a`;
  const b = `Bulkdel${unique}b`;

  const addPair = async (source: string, target: string) => {
    await addSourceInput().fill(source);
    await addSourceInput().press('Enter');
    await addTargetInput().fill(target);
    await addTargetInput().press('Enter');
    await expect(rowFor(target)).toBeVisible();
  };

  await addPair(a, `${a}hu`);
  await addPair(b, `${b}hu`);

  await page.getByRole('button', { name: 'Select all' }).click();
  await page.getByRole('button', { name: /^Delete selected rows|^Delete selected \(/ }).click();
  // The confirm dialog's own Delete-selected button.
  await page.locator('.modal-box').getByRole('button', { name: /^Delete selected/ }).click();

  await expect(rowFor(a)).toHaveCount(0);
  await expect(rowFor(b)).toHaveCount(0);
  await expect(page.locator('.alert-error')).toHaveCount(0);

  await page.reload();
  await expect(rowFor(a)).toHaveCount(0);
  await expect(rowFor(b)).toHaveCount(0);
});

test('multiselect: Delete selected words hard-deletes my own words and its dialog warns in red', async () => {
  const src = `Worddel${unique}`;
  const tgt = `${src}hu`;

  await addSourceInput().fill(src);
  await addSourceInput().press('Enter');
  await addTargetInput().fill(tgt);
  await addTargetInput().press('Enter');
  await expect(rowFor(src)).toBeVisible();

  await page.getByRole('button', { name: 'Select all' }).click();
  await page.getByRole('button', { name: /^Delete selected words \(/ }).click();

  // The dialog carries a red warning line.
  const warning = page.locator('.modal-box .text-error');
  await expect(warning).toBeVisible();

  await page.locator('.modal-box').getByRole('button', { name: /^Delete selected words \(/ }).click();

  await expect(rowFor(src)).toHaveCount(0);
  await expect(page.locator('.alert-error')).toHaveCount(0);

  // The word itself is gone from the dictionary — a fresh search does not find it.
  const found = await page.request.get(`/api/words?search=${encodeURIComponent(src.toLowerCase())}&language=de`);
  expect(found.ok()).toBeTruthy();
  const body = await found.json();
  expect(body.items.some((w: { text: string }) => w.text === src)).toBe(false);
});
