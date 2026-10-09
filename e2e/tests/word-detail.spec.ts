import { test, expect, type Page } from '@playwright/test';

// The word details page with no wordlist chosen yet. Every language is open and every translation can be
// marked, and marking one makes a wordlist of exactly that language pair: a wordlist takes only pairs of its
// own two languages, so any other one would refuse the mark. Once the wordlist exists it is the collect tag,
// and the languages it cannot take close.

const group = (p: Page, language: string) =>
  p.locator('.translation-group', { has: p.locator('.badge', { hasText: language }) });

test('marking a translation with no wordlist chosen makes a wordlist of that language pair', async ({ page }) => {
  // A fresh visitor: no account and no wordlist yet.
  await page.goto('/en/words?lang=de&target=en&q=mann');
  await page.locator('a.link.font-medium', { hasText: /^der Mann$/ }).first().click();
  await expect(page).toHaveURL(/\/en\/words\/\d+$/);

  // No wordlist: every group is open, and English and Hungarian both offer the control that marks an answer.
  const english = group(page, 'English');
  const hungarian = group(page, 'Hungarian');
  await expect(english.getByRole('button', { name: /^man\b/ }).first()).toBeVisible();
  await expect(hungarian.locator('button[aria-pressed]').first()).toBeVisible();
  await expect(page.locator('details.translation-group')).toHaveCount(0);

  // Marking the English answer mints a guest and a wordlist, German and English.
  await english.getByRole('button', { name: /^man\b/ }).first().click();
  await expect(english.getByRole('button', { name: /^man\b/ }).first()).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByLabel('Collect into').locator('option:checked')).toContainText('GER–ENG');

  // The new wordlist is the collect tag now, so Hungarian closes and loses its control.
  await expect(hungarian).toHaveJSProperty('open', false);
  await expect(hungarian).toContainText('Not in the selected wordlist');
  await expect(hungarian.locator('button[aria-pressed]')).toHaveCount(0);
});

// A language with many translations shows five, and a button for the rest.
test('a group shows five translations and a button for the rest', async ({ page }) => {
  await page.goto('/en/words?lang=de&target=en&q=machen');
  await page.locator('a.link.font-medium', { hasText: /^machen$/ }).first().click();
  await expect(page).toHaveURL(/\/en\/words\/\d+$/);

  const english = group(page, 'English');
  const marks = english.locator('button[aria-pressed]');
  await expect(marks).toHaveCount(5);
  const more = english.getByRole('button', { name: /^Show \d+ more$/ });
  const hidden = Number((await more.textContent())!.match(/\d+/)![0]);
  expect(hidden).toBeGreaterThan(0);

  await more.click();
  await expect(marks).toHaveCount(5 + hidden);
  await english.getByRole('button', { name: 'Show fewer' }).click();
  await expect(marks).toHaveCount(5);
});
