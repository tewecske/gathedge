import { test, expect, type Page } from '@playwright/test';

// The tag editor's add-a-row control (`/tags/new` -> `/tags/{id}`), driven every way a reader can:
// either box first, with and without the language swap, a full pair or a lone word, and the dictionary
// autocomplete (a real pick, the cross-box part-of-speech filter, and the translation suggestions).
//
// Requires the real stack (see playwright.config.ts). No external dictionary seed: every word the
// autocomplete needs is minted first through the editor's own "+ new" path or a bulk import.
//
// Regressions locked down (all found by hand in the running app):
//   - picking a row from the autocomplete now fills the field with that word (it used to leave the
//     half-typed letters, or a blank once the add cleared them);
//   - the word box is offered from either side, and a lone word can be entered from either side;
//   - typing in one box narrows the search to the other box's committed part of speech, both ways;
//   - the swap button re-orders the boxes with no server call, and a pair added while swapped still
//     lands in the tag's stored orientation.

test.describe.configure({ mode: 'serial' });

const U = Date.now().toString(36);

let page: Page;

const addRow = () => page.getByTestId('tag-add-row');
const srcInput = () => addRow().getByRole('textbox').first();
const tgtInput = () => addRow().getByRole('textbox').last();
const rowFor = (text: string) => page.locator('tbody tr').filter({ hasText: text });
// The picker's own autocomplete list — `menu menu-sm ... absolute`, distinct from the nav's `menu w-52` dropdowns.
const menu = () => addRow().locator('ul.menu-sm');
const menuItems = () => addRow().locator('ul.menu-sm li a');
const swap = () => page.getByRole('button', { name: 'Swap languages' });

const newTag = async () => {
  await page.goto('/en/tags/new');
  await expect(page).toHaveURL(/\/en\/tags\/\d+$/);
  await expect(page.getByRole('heading', { name: 'Add a word pair' })).toBeVisible();
};

const noErrors = () => expect(page.locator('.alert-error')).toHaveCount(0);

// Seed the shared dictionary directly, so the autocomplete has hits with a known language and part of speech.
// `translateTo` also writes a real translation edge (origin "user").
type Pos = 'Noun' | 'Verb' | 'Adjective' | 'Adverb' | 'Phrase' | 'Other';
const seedWord = async (
  language: 'De' | 'Hu',
  text: string,
  partOfSpeech: Pos,
  gender: 'Masculine' | 'Feminine' | 'Neuter' | null = null,
  translateTo?: { language: 'De' | 'Hu'; text: string },
) => {
  const res = await page.request.post('/api/words', {
    headers: { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' },
    data: {
      language,
      text,
      partOfSpeech,
      gender,
      translations: translateTo
        ? [{ language: translateTo.language, text: translateTo.text, partOfSpeech: null, gender: null }]
        : [],
      tagIds: [],
    },
  });
  expect(res.status()).toBe(201);
};

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

test.describe('entering a typed pair or lone word', () => {
  test('source box first, then the answer box', async () => {
    await newTag();
    const s = `s1src${U}`;
    const t = `s1tgt${U}`;
    await srcInput().fill(s);
    await srcInput().press('Enter');
    await expect(tgtInput()).toBeFocused();
    await tgtInput().fill(t);
    await tgtInput().press('Enter');
    await expect(rowFor(s)).toContainText(t);
    await page.reload();
    await expect(rowFor(s)).toContainText(t);
    await noErrors();
  });

  test('answer box first, then the word box', async () => {
    await newTag();
    const s = `s2src${U}`;
    const t = `s2tgt${U}`;
    await tgtInput().fill(t);
    await tgtInput().press('Enter');
    await expect(srcInput()).toBeFocused();
    await srcInput().fill(s);
    await srcInput().press('Enter');
    await expect(rowFor(s)).toContainText(t);
    await page.reload();
    await expect(rowFor(s)).toContainText(t);
    await noErrors();
  });

  test('swap languages, then word box first — the pair lands in the stored orientation', async () => {
    await newTag();
    await swap().click();
    await expect(srcInput()).toHaveAttribute('placeholder', 'Type a Hungarian word');
    await expect(tgtInput()).toHaveAttribute('placeholder', 'Type a German word');
    const left = `s3hu${U}`;
    const right = `s3de${U}`;
    await srcInput().fill(left);
    await srcInput().press('Enter');
    await tgtInput().fill(right);
    await tgtInput().press('Enter');
    await expect(rowFor(left)).toContainText(right);
    // The swap was a view change only: after a reload the tag is back to de -> hu and the row carries both words.
    await page.reload();
    await expect(rowFor(right)).toContainText(left);
    await noErrors();
  });

  test('swap languages, then answer box first', async () => {
    await newTag();
    await swap().click();
    const left = `s4hu${U}`;
    const right = `s4de${U}`;
    await tgtInput().fill(right);
    await tgtInput().press('Enter');
    await expect(srcInput()).toBeFocused();
    await srcInput().fill(left);
    await srcInput().press('Enter');
    await expect(rowFor(right)).toContainText(left);
    await page.reload();
    await expect(rowFor(right)).toContainText(left);
    await noErrors();
  });

  test('a lone word from the word box (empty answer box + Enter)', async () => {
    await newTag();
    const s = `s5lone${U}`;
    await srcInput().fill(s);
    await srcInput().press('Enter');
    await expect(tgtInput()).toBeFocused();
    await tgtInput().press('Enter');
    await expect(rowFor(s)).toContainText('—');
    await page.reload();
    await expect(rowFor(s)).toContainText('—');
    await noErrors();
  });

  test('a lone word from the answer box (empty word box + Enter)', async () => {
    await newTag();
    const t = `s6lone${U}`;
    await tgtInput().fill(t);
    await tgtInput().press('Enter');
    await expect(srcInput()).toBeFocused();
    await srcInput().press('Enter');
    await expect(rowFor(t)).toContainText('—');
    await page.reload();
    await expect(rowFor(t)).toContainText('—');
    await noErrors();
  });
});

test.describe('dictionary autocomplete', () => {
  const P = `zz${U}`;
  const deNoun = `${P}n`; // German, Noun
  const deOther = `${P}o`; // German, Other
  const huWord = `${P}h`; // Hungarian, Other

  test.beforeAll(async () => {
    await seedWord('De', deNoun, 'Noun', 'Masculine');
    await seedWord('De', deOther, 'Other');
    await seedWord('Hu', huWord, 'Other');
  });

  test('picking a row from the autocomplete fills the field with that word', async () => {
    await newTag();
    await tgtInput().fill(P);
    await expect(menu()).toBeVisible();
    await menuItems().filter({ hasText: huWord }).click();
    await expect(tgtInput()).toHaveValue(huWord);
    await noErrors();
  });

  test('the search is narrowed to the other box’s part of speech', async () => {
    await newTag();

    // Nothing committed yet: the German side offers both the noun and the "other" word.
    await srcInput().fill(P);
    await expect(menu()).toBeVisible();
    await expect(menuItems().filter({ hasText: deNoun })).toBeVisible();
    await expect(menuItems().filter({ hasText: deOther })).toBeVisible();

    // Start over: commit the Hungarian word (part of speech "Other") in the answer box first.
    await newTag();
    await tgtInput().fill(P);
    await expect(menu()).toBeVisible();
    await menuItems().filter({ hasText: huWord }).click();
    await expect(srcInput()).toBeFocused();

    // The German search now carries pos=Other, so only the "other" word comes back.
    const search = page.waitForResponse(
      (r) => r.url().includes('/api/words?') && new URL(r.url()).searchParams.get('lang') === 'de',
    );
    await srcInput().fill(P);
    const url = new URL((await search).url());
    expect(url.searchParams.get('pos')).toBe('other');

    await expect(menuItems().filter({ hasText: deOther })).toBeVisible();
    const texts = await menuItems().allInnerTexts();
    expect(texts.some((t) => t.includes(deOther))).toBe(true);
    expect(texts.some((t) => t.includes(deNoun))).toBe(false);
    await noErrors();
  });
});

test.describe('translation suggestions from the other box', () => {
  const pfx = `zztr${U}`;
  const de = `${pfx}de`;
  const hu = `${pfx}hu`;

  test.beforeAll(async () => {
    await seedWord('De', de, 'Other', null, { language: 'Hu', text: hu });
  });

  // Pick a dictionary row: type the prefix (so the "+ new" row, which shows only the prefix, is not a `hasText`
  // match for the full word) and wait for the real row before clicking.
  const pick = async (input: ReturnType<typeof srcInput>, word: string) => {
    await input.fill(pfx);
    const row = menu().locator('li a', { hasText: word });
    await expect(row).toBeVisible();
    await row.click();
    await expect(input).toHaveValue(word);
  };

  test('committing the word offers its translation in the answer box, no typing', async () => {
    await newTag();
    await pick(srcInput(), de);
    await expect(tgtInput()).toBeFocused();
    await expect(menu()).toBeVisible();
    expect((await menuItems().allInnerTexts()).some((t) => t.includes(hu))).toBe(true);
    await tgtInput().press('Enter');
    await expect(rowFor(de)).toContainText(hu);
    await noErrors();
  });

  test('committing the answer offers its translation back in the word box', async () => {
    await newTag();
    await pick(tgtInput(), hu);
    await expect(srcInput()).toBeFocused();
    await expect(menu()).toBeVisible();
    expect((await menuItems().allInnerTexts()).some((t) => t.includes(de))).toBe(true);
    await noErrors();
  });
});
