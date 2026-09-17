import { test, expect, type Page } from '@playwright/test';

// The tag editor's page control (`/tags/{id}`, `?page=`, `?size=`, `?match=`), on a wordlist long enough to need one.
//
// The database cuts the page and applies the chips, so what is worth locking down is that the address, the request and
// what is on screen all say the same thing:
//   - a page holds the editor's own fifty words, and the page number is in the URL (so the back button works);
//   - a page past the end corrects itself to the last one rather than showing an empty table;
//   - a chip is part of the address, and narrowing gives up the page it was on;
//   - a row added at the bottom is on the page the editor turns to, not on a page nobody is looking at.
//
// Requires the real stack (see playwright.config.ts). The 120 rows go in through `tabular-import` rather than through
// the add row: this spec is about paging, and typing 120 pairs would test the autocomplete instead.

test.describe.configure({ mode: 'serial' });

const unique = Date.now();
const rows = 120;

let page: Page;
let tagId = 0;

const headers = { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' };

const bodyRows = () => page.locator('tbody tr');
const firstRow = () => bodyRows().first();
const addSourceInput = () => page.locator('input[placeholder="Type a German word"]').first();
const addTargetInput = () => page.locator('input[placeholder="Type a Hungarian word"]').first();

/** `n000`…`n119`, so the row a page starts with names the page it belongs to. */
const word = (index: number) => `Pgsrc${unique}n${String(index).padStart(3, '0')}`;

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
  // A guest, so the writes below have a session — the same mint `tag-editor.spec.ts` makes.
  const guest = await page.request.post('/api/guest', { headers, data: { theme: 'Light' } });
  expect(guest.status()).toBe(201);

  const created = await page.request.post('/api/tags', {
    headers,
    data: { name: `Paging${unique}`, sourceLanguage: 'De', targetLanguage: 'Hu' },
  });
  expect(created.status()).toBe(201);
  tagId = (await created.json()).tag.id;

  const imported = await page.request.post(`/api/tags/${tagId}/tabular-import`, {
    headers,
    data: {
      rows: Array.from({ length: rows }, (_, index) => ({
        source: word(index),
        target: `pgtgt${unique}n${String(index).padStart(3, '0')}`,
        sourceExtra: null,
        targetExtra: null,
      })),
      sourceLanguage: 'De',
      targetLanguage: 'Hu',
    },
  });
  expect(imported.status()).toBe(200);
  expect((await imported.json()).rows).toBe(rows);
});

test.afterAll(async () => {
  await page.close();
});

test('the rows are cut into pages of fifty, and the page is in the address', async () => {
  await page.goto(`/en/tags/${tagId}`);

  await expect(page.getByText(`${rows} words`)).toBeVisible();
  await expect(page.getByText('Page 1 of 3')).toBeVisible();
  await expect(bodyRows()).toHaveCount(50);
  await expect(firstRow()).toContainText(word(0));

  await page.getByRole('button', { name: '2', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}\\?page=2$`));
  await expect(firstRow()).toContainText(word(50));

  // The first page is the bare path, so the back button leaves no `?page=1` behind.
  await page.goBack();
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}$`));
  await expect(firstRow()).toContainText(word(0));
});

test('the page size is the reader’s, and a page past the end corrects itself', async () => {
  await page.goto(`/en/tags/${tagId}`);

  await page.getByRole('combobox', { name: 'Rows per page' }).selectOption('100');
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}\\?size=100$`));
  await expect(page.getByText('Page 1 of 2')).toBeVisible();
  await expect(bodyRows()).toHaveCount(100);

  // The server answers an empty page with an honest total; the browser replaces the address with the last page,
  // rather than leaving the reader looking at an empty table.
  await page.goto(`/en/tags/${tagId}?page=99`);
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}\\?page=3$`));
  await expect(page.getByText('Page 3 of 3')).toBeVisible();
  await expect(bodyRows()).toHaveCount(rows - 100);
  await expect(firstRow()).toContainText(word(100));
});

test('a chip is part of the address, and an added row is on the page shown', async () => {
  await page.goto(`/en/tags/${tagId}?page=2`);
  await expect(firstRow()).toContainText(word(50));

  // Every row here was written by the tabular import, so "Paired rows" narrows to all of them — the chip still
  // starts again at page one, and the address says which chip is on.
  await page.getByRole('button', { name: 'Paired rows' }).click();
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}\\?match=paired$`));
  await expect(firstRow()).toContainText(word(0));
  await expect(page.getByText(`${rows} words`)).toBeVisible();

  // A chip that matches nothing here empties the listing rather than failing it.
  await page.getByRole('button', { name: 'Verified matches' }).click();
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}\\?match=verified%2Cpaired$`));
  await page.getByRole('button', { name: 'Paired rows' }).click();
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}\\?match=verified$`));
  await expect(page.getByText('No words yet.')).toBeVisible();
  await page.getByRole('button', { name: 'Verified matches' }).click();
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}$`));

  const added = `Pgadd${unique}`;
  await addSourceInput().fill(added);
  await addSourceInput().press('Enter');
  await addTargetInput().fill(`pgadd${unique}`);
  await addTargetInput().press('Enter');

  // Appended, so it is on the last page — and the editor turns to it rather than leaving the reader on page one.
  await expect(page).toHaveURL(new RegExp(`/en/tags/${tagId}\\?page=3$`));
  await expect(bodyRows().filter({ hasText: added })).toBeVisible();
  await expect(page.getByText(`${rows + 1} words`)).toBeVisible();
});
