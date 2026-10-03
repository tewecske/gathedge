import { test, expect } from '@playwright/test';

// The achievements, end to end: a play of ten words is the smallest play that counts toward an achievement
// (`Achievements.qualifyingWordCount`). Finishing one shows the tiers it unlocked on the result screen, and the
// profile's achievements tab then shows them too.
//
// Requires the real stack (see playwright.config.ts). The ten words go in through `tabular-import`, the same as
// tag-editor-paging.spec.ts: this spec is about the achievements, not the editor. Each word is new to the
// dictionary, so the one correct answer to every prompt is known.

const unique = Date.now();
const headers = { 'X-Requested-With': 'XMLHttpRequest', 'Content-Type': 'application/json' };

const words = Array.from({ length: 10 }, (_, index) => ({
  term: `Ach${unique}n${index}`,
  answer: `ach${unique}v${index}`,
}));

test('a qualifying play unlocks a tier, and the achievements tab shows it', async ({ page }) => {
  // Ten typed answers, each held on screen for a moment, outgrow Playwright's 30s default.
  test.setTimeout(90000);

  // A guest, so the writes below have a session.
  const guest = await page.request.post('/api/guest', { headers, data: { theme: 'Light' } });
  expect(guest.status()).toBe(201);

  const created = await page.request.post('/api/tags', {
    headers,
    data: { name: `Achievements${unique}`, sourceLanguage: 'De', targetLanguage: 'Hu' },
  });
  expect(created.status()).toBe(201);
  const tagId = (await created.json()).tag.id;

  const imported = await page.request.post(`/api/tags/${tagId}/tabular-import`, {
    headers,
    data: {
      rows: words.map((w) => ({ source: w.term, target: w.answer, sourceExtra: null, targetExtra: null })),
      sourceLanguage: 'De',
      targetLanguage: 'Hu',
    },
  });
  expect(imported.status()).toBe(200);

  const game = await page.request.post('/api/games', {
    headers,
    data: { sourceLanguage: 'De', targetLanguage: 'Hu', tagIds: [tagId] },
  });
  expect(game.status()).toBe(201);
  const slug = (await game.json()).slug;

  // Before the play, the tab lists every achievement, also the ones not started.
  await page.goto('/en/profile/achievements');
  const gamesPlayed = page.locator('li.list-row', {
    has: page.getByRole('heading', { name: 'Games played', exact: true }),
  });
  await expect(gamesPlayed).toContainText('Not started');
  await expect(gamesPlayed).toContainText('0 / 1');
  await expect(page.locator('li.list-row', { hasText: 'Marathon' })).toContainText('Not done yet');

  // The play: every word of the wordlist, typed.
  await page.goto(`/en/g/${slug}`);
  await page.getByRole('button', { name: 'Start' }).click();
  await expect(page).toHaveURL(/\/en\/g\/[a-z0-9-]+\/play\/\d+$/);

  // The prompt's own heading classes, since the guest banner and the game header are `h2`s too (see game.spec.ts).
  const heading = page.locator('h2.text-xl.font-semibold');
  for (let i = 0; i < words.length; i++) {
    await expect(heading).toHaveText(/\S/);
    const promptText = ((await heading.textContent()) ?? '').trim();
    const match = words.find((w) => w.term === promptText);
    expect(match, `unexpected quiz prompt: "${promptText}"`).toBeTruthy();

    await page.getByPlaceholder('Type the translation').fill(match!.answer);
    // No click on "Next": the play moves on by itself after a correct answer, and a click that came after that
    // would land on the next word's "Next".
    await page.getByRole('button', { name: 'Submit' }).click();

    if (i < words.length - 1) {
      await expect(heading).not.toHaveText(promptText);
    }
  }

  // The result screen shows what the play unlocked, with no request of its own.
  await expect(page.getByText('Quiz complete')).toBeVisible();
  const unlocked = page.getByRole('status').filter({ hasText: 'New achievements' });
  await expect(unlocked).toBeVisible();
  await expect(unlocked.getByText('Games played: tier 1', { exact: true })).toBeVisible();

  // Its link opens the profile's achievements tab, where the same tier now shows.
  await unlocked.getByRole('link', { name: 'See all achievements' }).click();
  await expect(page).toHaveURL(/\/en\/profile\/achievements$/);
  await expect(page.locator('.tab-active')).toHaveText('Achievements');
  await expect(gamesPlayed).toContainText('Tier 1');
  await expect(gamesPlayed).toContainText('1 / 5');
  await expect(gamesPlayed.locator('progress')).toHaveAttribute('max', '5');
});
