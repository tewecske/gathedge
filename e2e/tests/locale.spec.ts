import { test, expect } from '@playwright/test';

// The language prefix, in a real browser. Requires the full stack (see playwright.config.ts).
//
// These cover the parts no unit test can reach: index.html's boot script reading
// navigator.languages, Waypoint matching a prefixed URL on arrival, and the picker being a real
// navigation rather than a pushState. The precedence *rule* itself is LocaleSyncSpec's job; what is
// checked here is that a browser actually behaves the way that rule assumes.

test.describe('language prefix', () => {
  test('a bare URL is redirected to a prefix chosen from the browser languages', async ({ browser }) => {
    const hu = await browser.newContext({ locale: 'hu-HU' });
    const page = await hu.newPage();
    await page.goto('/');
    await expect(page).toHaveURL(/\/hu\//);
    await expect(page.locator('html')).toHaveAttribute('lang', 'hu');
    await hu.close();
  });

  test('an unsupported browser language falls back to English', async ({ browser }) => {
    const de = await browser.newContext({ locale: 'de-DE' });
    const page = await de.newPage();
    await page.goto('/');
    await expect(page).toHaveURL(/\/en\//);
    await de.close();
  });

  // The rule that is easiest to get backwards: an explicit prefix is the visitor's stated choice,
  // and nothing — not the browser's languages, not the account's stored preference — may override it.
  test('an explicit prefix is never overridden by the browser language', async ({ browser }) => {
    const hu = await browser.newContext({ locale: 'hu-HU' });
    const page = await hu.newPage();
    await page.goto('/en/sign-in');
    await expect(page).toHaveURL(/\/en\/sign-in$/);
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await hu.close();
  });

  // A deep link has to survive the redirect intact — this is the shape every emailed
  // link arrives in.
  test('a prefixed deep link renders its page rather than the not-found page', async ({ page }) => {
    await page.goto('/hu/verify-email/not-a-real-token');
    await expect(page).toHaveURL(/\/hu\/verify-email\/not-a-real-token$/);
    await expect(page.getByText(/404|not found/i)).toHaveCount(0);
  });

  test('the picker switches language and keeps the path', async ({ page }) => {
    await page.goto('/en/sign-in');
    // The picker is a popover dropdown: the trigger shows the current language's code, the entries
    // live in the popover and are only clickable once it is open.
    await page.getByRole('button', { name: 'Language' }).click();
    await page.getByRole('link', { name: 'Magyar' }).click();
    await expect(page).toHaveURL(/\/hu\/sign-in$/);
    await expect(page.locator('html')).toHaveAttribute('lang', 'hu');
  });
});
