import { test, expect, type Page } from '@playwright/test';

// Full-stack golden-path smoke test. Requires the real stack running (see
// playwright.config.ts) — Postgres via docker compose, backend, and vite dev.
//
// Note on group invites: EmailSender is log-based in dev (summary.md/M3 plan), so
// the invite link only ever reaches the backend's stdout, not anything a browser
// automation script can observe. This suite verifies the invite *request* succeeds
// (UI feedback), not clicking the emailed link — the full invite -> accept round
// trip is covered by GroupServiceSpec (backend, SQLite) instead.

const unique = Date.now();
const email = `e2e-${unique}@example.com`;
const password = 'password123';

test.describe.configure({ mode: 'serial' });

let page: Page;

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
});

test.afterAll(async () => {
  await page.close();
});

test('unauthenticated visitor is redirected to sign-in', async () => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/sign-in$/);
});

test('sign up creates an account and lands on the Todo board', async () => {
  await page.goto('/sign-up');
  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole('heading', { name: 'TODO' })).toBeVisible();
});

test('todo item can be added and cycled through statuses by clicking', async () => {
  const itemText = `write the report ${unique}`;
  await page.locator('input[placeholder="New to-do item"]').fill(itemText);
  await page.getByRole('button', { name: 'Add' }).click();

  const toDoColumn = page.locator('.card').filter({ hasText: 'To Do' });
  await expect(toDoColumn.getByText(itemText)).toBeVisible();

  await toDoColumn.getByText(itemText).click();
  const inProgressColumn = page.locator('.card').filter({ hasText: 'In Progress' });
  await expect(inProgressColumn.getByText(itemText)).toBeVisible();
});

test('blank todo submission is a no-op', async () => {
  const before = await page.locator('.list-row').count();
  await page.getByRole('button', { name: 'Add' }).click();
  const after = await page.locator('.list-row').count();
  expect(after).toBe(before);
});

test('theme toggle switches the page theme immediately', async () => {
  const html = page.locator('html');
  const before = await html.getAttribute('data-theme');
  await page.getByRole('button', { name: /Switch to/ }).click();
  await expect(html).not.toHaveAttribute('data-theme', before ?? '');
});

test('creating a group makes the creator its admin', async () => {
  const groupName = `E2E Group ${unique}`;
  await page.getByRole('link', { name: 'Groups' }).click();
  await expect(page).toHaveURL(/\/groups$/);
  await page.locator('input[placeholder="New group name"]').fill(groupName);
  await page.getByRole('button', { name: 'Create group' }).click();
  const row = page.locator('.list-row').filter({ hasText: groupName });
  await expect(row).toBeVisible();
  await expect(row.getByText('Admin')).toBeVisible();
  await row.getByRole('link', { name: groupName }).click();
  await expect(page.getByRole('heading', { name: groupName })).toBeVisible();
});

test('adding a source/target pair requires both fields and then appears in the table', async () => {
  await page.getByRole('button', { name: 'Add' }).click(); // blank submit: no-op
  await expect(page.locator('table tbody tr')).toHaveCount(0);

  await page.locator('input[placeholder="Source"]').fill('source-value');
  await page.locator('input[placeholder="Target"]').fill('target-value');
  await page.getByRole('button', { name: 'Add' }).click();

  await expect(page.locator('table tbody tr', { hasText: 'source-value' })).toBeVisible();
});

test('inviting a member is accepted by the server (link delivery is out of e2e reach)', async () => {
  await page.locator('input[placeholder="Email to invite"]').fill(`invitee-${unique}@example.com`);
  await page.getByRole('button', { name: 'Invite' }).click();
  await expect(page.getByText(/Invited /)).toBeVisible();
});

test('log out returns to sign-in', async () => {
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/sign-in$/);
});

test('log back in with the same credentials', async () => {
  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/$/);
});

test('a non-admin is denied access to the admin area', async () => {
  await page.goto('/admin/users');
  await expect(page.getByText(/administrator rights/)).toBeVisible();
});

test.describe('administrator flows', () => {
  test.describe.configure({ mode: 'serial' });

  test('the bootstrap admin can sign in and manage users', async () => {
    await page.getByRole('button', { name: 'Log out' }).click().catch(() => {});
    await page.goto('/sign-in');
    await page.locator('input[type=email]').fill(process.env.BOOTSTRAP_ADMIN_EMAIL ?? 'admin@example.com');
    await page.locator('input[type=password]').fill(process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'changeme123');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.getByRole('link', { name: 'Admin' }).click();
    await expect(page.getByRole('heading', { name: 'User management' })).toBeVisible();
    await expect(page.getByText(email)).toBeVisible();
  });

  test('creating a user from the admin panel', async () => {
    const newAdminUserEmail = `admin-created-${unique}@example.com`;
    await page.locator('input[placeholder="Email"]').fill(newAdminUserEmail);
    await page.locator('input[placeholder="Password (min 8 characters)"]').fill('password123');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page.getByText(newAdminUserEmail)).toBeVisible();
  });

  test('editing and deleting a user, with confirmation before delete', async () => {
    const row = page.locator('tr', { hasText: email });
    await row.click();
    await expect(page.getByRole('button', { name: 'Delete user' })).toBeVisible();

    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Delete user' }).click();
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(page.getByText(email)).not.toBeVisible();
  });
});
