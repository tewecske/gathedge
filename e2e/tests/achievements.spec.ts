import { test, expect } from '@playwright/test';

// The achievements, end to end: a play of ten words is the smallest play that counts toward an achievement
// (`Achievements.qualifyingWordCount`). Finishing one opens a dialog on the tiers it unlocked, and the
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
  // A new account starts at level 1 with no XP.
  await expect(page.getByRole('heading', { name: 'Level 1' })).toBeVisible();
  await expect(page.getByText('0 / 100 XP')).toBeVisible();

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

  // The result screen opens a dialog on what the play unlocked, one tier per page, with no request of its own.
  await expect(page.getByText('Quiz complete')).toBeVisible();
  const dialog = page.locator('.modal-open .modal-box');
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText('Achievement unlocked');

  // Catalog order: "Games played" comes first, then the per-game-type and the perfect-game tiers of this play.
  await expect(dialog.getByText('Games played', { exact: true })).toBeVisible();
  await expect(dialog).toContainText('Tier 1');
  await expect(dialog).toContainText('Reached: 1');
  await expect(dialog).toContainText('Next tier: 5');
  // A perfect 10-word play: 25 for the play, 10 correct answers (4 each), and tier 1 of games played (25), typing
  // (25) and perfect games (100). Level 2 starts at 100 XP, so a level-up page ends the dialog.
  await expect(dialog).toContainText('+215 XP');

  const seen: string[] = [];
  for (;;) {
    seen.push(((await dialog.locator('p.text-xl').textContent()) ?? '').trim());
    const next = dialog.getByRole('button', { name: 'Next', exact: true });
    if ((await next.count()) === 0) break;
    await next.click();
    await expect(dialog.locator('p.text-xl')).not.toHaveText(seen[seen.length - 1]);
  }
  expect(seen).toContain('Games played: Type the answer');
  expect(seen).toContain('Perfect games');
  expect(seen).toContain('New level: 2');

  await dialog.getByRole('button', { name: 'Close', exact: true }).click();
  await expect(page.locator('.modal-open')).toHaveCount(0);

  // The profile's achievements tab now shows the same tier. The profile link sits in the account menu, so the test
  // opens the profile by its address and follows the tab from there.
  await page.goto('/en/profile');
  await page.locator('.tab', { hasText: 'Achievements' }).click();
  await expect(page).toHaveURL(/\/en\/profile\/achievements$/);
  await expect(page.locator('.tab-active')).toHaveText('Achievements');
  await expect(gamesPlayed).toContainText('Tier 1');
  await expect(gamesPlayed).toContainText('1 / 5');
  await expect(gamesPlayed.locator('progress')).toHaveAttribute('max', '5');
  await expect(page.getByRole('heading', { name: 'Level 2' })).toBeVisible();
  await expect(page.getByText('115 / 200 XP')).toBeVisible();
});
