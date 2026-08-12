import { test, expect, type Page } from '@playwright/test';

// Every screen, in both languages, checked for the two ways a translation goes missing.
//
// The catalogs are JSON, so a key that no catalog defines is a runtime bug rather than a compile
// error. `MessagesSpec` covers the half it can see — that every `UiKeys`/`MessageKeys` constant
// exists in `en` and `hu` — but it cannot see a page that passes a key nobody registered, or one
// built by string concatenation. Those show up here in exactly two ways, and this suite watches for
// both: the key renders as itself (`ui.…` on screen), and `I18n.t` writes `i18n: no '…'` to the
// console. The frontend's jsdom specs cannot do this — they load no catalog at all, so *every*
// message resolves to its key there.
//
// Hungarian is the point of the exercise, but English is walked too: a key missing from both
// catalogs is invisible if only the translated language is checked.

const adminEmail = process.env.BOOTSTRAP_ADMIN_EMAIL ?? 'admin@example.com';
const adminPassword = process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'changeme123';

// Signed-out pages first, then the ones behind a session. `/en` and `/hu` are the same list: the
// prefix is the only thing that decides the language.
const publicPaths = ['/sign-in', '/sign-up', '/check-inbox', '/verify-email/not-a-real-token', '/forbidden'];
const signedInPaths = ['/', '/settings', '/admin/users', '/admin/audit', '/admin/system'];

/** Catalog misses seen since the page was opened, as `I18n.t` reports them. */
function collectMissingKeys(page: Page): string[] {
  const missing: string[] = [];
  page.on('console', message => {
    const text = message.text();
    if (text.startsWith('i18n: no ')) {
      missing.push(text);
    }
  });
  return missing;
}

/** Any `ui.`-prefixed key that reached the screen as its own name. */
async function untranslatedText(page: Page): Promise<string[]> {
  const body = (await page.locator('body').innerText()) ?? '';
  return body.match(/\bui\.[a-zA-Z0-9.]+/g) ?? [];
}

async function signIn(page: Page, prefix: string): Promise<void> {
  await page.goto(`${prefix}/sign-in`);
  await page.locator('input[type=email]').fill(adminEmail);
  await page.locator('input[type=password]').fill(adminPassword);
  await page.locator('button[type=submit]').click();
  await expect(page).toHaveURL(new RegExp(`${prefix}/$`));
}

for (const prefix of ['/en', '/hu']) {
  test.describe(`${prefix} renders no untranslated copy`, () => {
    test.describe.configure({ mode: 'serial' });

    let page: Page;
    let missing: string[];

    test.beforeAll(async ({ browser }) => {
      page = await browser.newPage();
      missing = collectMissingKeys(page);
    });

    test.afterAll(async () => {
      await page.close();
    });

    test('the signed-out pages', async () => {
      for (const path of publicPaths) {
        await page.goto(`${prefix}${path}`);
        // Every one of these pages leads with an `h1`, which is the cheapest proof the bundle
        // mounted and rendered rather than throwing on the way up.
        await expect(page.locator('h1')).toBeVisible();
        expect(await untranslatedText(page), `raw keys on ${prefix}${path}`).toEqual([]);
      }
    });

    test('the pages behind a session, including the whole admin surface', async () => {
      await signIn(page, prefix);
      for (const path of signedInPaths) {
        await page.goto(`${prefix}${path}`);
        // Wait for the page's own heading, so the assertion runs against a mounted page rather
        // than the loading spinner `App` shows until /api/me answers.
        await expect(page.locator('h1')).toBeVisible();
        expect(await untranslatedText(page), `raw keys on ${prefix}${path}`).toEqual([]);
      }
    });

    // Deliberately last: it reports every miss the two walks above accumulated, in one place. A key
    // can be absent from the catalog and still not be caught by the text check — an `aria-label`,
    // a `placeholder`, a `confirm()` string — and this is what sees those.
    test('no page asked for a key the catalog does not have', async () => {
      expect(missing, 'catalog misses reported by I18n').toEqual([]);
    });
  });
}
