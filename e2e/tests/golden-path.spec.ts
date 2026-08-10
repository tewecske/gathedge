import { test, expect, type Page } from '@playwright/test';

// Full-stack golden-path smoke test. Requires the real stack running (see
// playwright.config.ts) — Postgres via docker compose, backend, and vite dev.
//
// Note on group invites: EmailSender is log-based in dev (summary.md/M3 plan), so
// the invite link only ever reaches the backend's stdout, not anything a browser
// automation script can observe. This suite verifies the invite *request* succeeds
// (UI feedback), not clicking the emailed link — the full invite -> accept round
// trip is covered by GroupServiceSpec (backend, SQLite) instead.
//
// Email verification is the same story, twice over: the link is only ever logged, and
// REQUIRE_EMAIL_VERIFICATION defaults to false, so signup below still lands on the todo
// board. Turning it on would send this suite to /check-inbox with no way to continue —
// the verify -> login round trip is in AuthServiceSpec, which can read the sent mail.

const unique = Date.now();
const email = `e2e-${unique}@example.com`;
const password = 'password123';

test.describe.configure({ mode: 'serial' });

let page: Page;
let groupName: string;

test.beforeAll(async ({ browser }) => {
  page = await browser.newPage();
});

test.afterAll(async () => {
  await page.close();
});

test('unauthenticated visitor is redirected to sign-in', async () => {
  // Deliberately bare: this is the one place the boot script's prefix redirect is exercised.
  await page.goto('/');
  await expect(page).toHaveURL(/\/en\/sign-in$/);
});

test('sign up creates an account and lands on the Todo board', async () => {
  await page.goto('/en/sign-up');
  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Sign up' }).click();
  await expect(page).toHaveURL(/\/en\/$/);
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

test('todo item survives a page refresh (list is re-fetched, not just held in memory)', async () => {
  const itemText = `write the report ${unique}`;
  await page.reload();
  const inProgressColumn = page.locator('.card').filter({ hasText: 'In Progress' });
  await expect(inProgressColumn.getByText(itemText)).toBeVisible();
});

test('theme toggle switches the page theme immediately', async () => {
  const html = page.locator('html');
  const before = await html.getAttribute('data-theme');
  await page.getByRole('button', { name: /Switch to/ }).click();
  await expect(html).not.toHaveAttribute('data-theme', before ?? '');
});

test('creating a group makes the creator its admin', async () => {
  groupName = `E2E Group ${unique}`;
  await page.getByRole('link', { name: 'Groups' }).click();
  await expect(page).toHaveURL(/\/en\/groups$/);
  await page.locator('input[placeholder="New group name"]').fill(groupName);
  await page.getByRole('button', { name: 'Create group' }).click();
  const row = page.locator('.list-row').filter({ hasText: groupName });
  await expect(row).toBeVisible();
  await expect(row.getByText('Admin')).toBeVisible();
  await row.getByRole('link', { name: groupName }).click();
  await expect(page.getByRole('heading', { name: groupName })).toBeVisible();
});

test('group list survives a page refresh (list is re-fetched, not just held in memory)', async () => {
  await page.goto('/en/groups');
  const row = page.locator('.list-row').filter({ hasText: groupName });
  await expect(row).toBeVisible();
  await row.getByRole('link', { name: groupName }).click();
  await expect(page.getByRole('heading', { name: groupName })).toBeVisible();
});

test('adding a source/target pair requires both fields and then appears in the table', async () => {
  // Scoped to the pairs table specifically: the page also has a members table
  // (which always has at least 1 row, the creator), so an unscoped `table tbody
  // tr` locator would false-positive on that.
  const pairsTable = page.locator('table').filter({ has: page.locator('th', { hasText: 'Source' }) });

  await page.getByRole('button', { name: 'Add' }).click(); // blank submit: no-op
  await expect(pairsTable.locator('tbody tr')).toHaveCount(0);

  await page.locator('input[placeholder="Source"]').fill('source-value');
  await page.locator('input[placeholder="Target"]').fill('target-value');
  await page.getByRole('button', { name: 'Add' }).click();

  await expect(pairsTable.locator('tbody tr', { hasText: 'source-value' })).toBeVisible();
});

test('inviting a member is accepted by the server (link delivery is out of e2e reach)', async () => {
  await page.locator('input[placeholder="Email to invite"]').fill(`invitee-${unique}@example.com`);
  await page.getByRole('button', { name: 'Invite' }).click();
  await expect(page.getByText(/Invited /)).toBeVisible();
});

test('group detail (pairs, members, add-pair form) survives a page refresh', async () => {
  await page.reload();
  // Add-pair form is only rendered once the group (with myRole) has loaded.
  await expect(page.locator('input[placeholder="Source"]')).toBeVisible();
  await expect(page.locator('input[placeholder="Target"]')).toBeVisible();
  await expect(page.locator('table tbody tr', { hasText: 'source-value' })).toBeVisible();
  // Members list must include the creator (self) — this is the "empty members" bug.
  await expect(page.getByText(email)).toBeVisible();
});

test('log out returns to sign-in', async () => {
  // Log out lives in the avatar dropdown (a popover), so the menu has to be opened first.
  await page.getByRole('button', { name: 'Account menu' }).click();
  await page.getByRole('button', { name: 'Log out' }).click();
  await expect(page).toHaveURL(/\/en\/sign-in$/);
});

test('log back in with the same credentials', async () => {
  await page.locator('input[type=email]').fill(email);
  await page.locator('input[type=password]').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/en\/$/);
});

test('a non-admin is denied access to the admin area', async () => {
  await page.goto('/en/admin/users');
  await expect(page.getByText(/administrator rights/)).toBeVisible();
});

test.describe('administrator flows', () => {
  test.describe.configure({ mode: 'serial' });

  test('the bootstrap admin can sign in and manage users', async () => {
    await page
      .getByRole('button', { name: 'Account menu' })
      .click()
      .then(() => page.getByRole('button', { name: 'Log out' }).click())
      .catch(() => {});
    await page.goto('/en/sign-in');
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

  test('the user list reports whether each address is confirmed and whether sign-in is locked', async () => {
    const row = page.locator('tr', { hasText: email });
    await expect(row.getByText(/Confirmed|Unconfirmed/)).toBeVisible();
    await expect(row.getByText('OK')).toBeVisible();
  });

  test('the account page shows the diagnostics an administrator answers a support ticket with', async () => {
    const row = page.locator('tr', { hasText: email });
    // The email cell is a real link (keyboard-reachable), not a click handler on the row.
    await row.getByRole('link', { name: email }).click();
    await expect(page.getByRole('heading', { name: 'Email confirmation' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Sign-in security' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Sessions' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Linked social accounts' })).toBeVisible();
    // This account has signed in during this run, so its history is not empty.
    await expect(page.getByText('Signed in').first()).toBeVisible();
  });

  test('the system overview reports the deployment without exposing any secret', async () => {
    await page.getByRole('link', { name: 'System' }).click();
    await expect(page).toHaveURL(/\/en\/admin\/system$/);
    await expect(page.getByRole('heading', { name: 'System overview' })).toBeVisible();
    await expect(page.getByText('Configuration')).toBeVisible();
    await expect(page.getByText('Statistics')).toBeVisible();
    await expect(page.getByText('Accounts', { exact: true })).toBeVisible();
    // The dev stack runs without SMTP, which is why a confirmation link is logged rather than delivered.
    await expect(page.getByText('logged, not sent')).toBeVisible();
    await expect(page.getByText(process.env.BOOTSTRAP_ADMIN_PASSWORD ?? 'changeme123')).not.toBeVisible();
  });

  test('the audit log lists the actions taken so far in this run', async () => {
    await page.getByRole('link', { name: 'Audit log' }).click();
    await expect(page).toHaveURL(/\/en\/admin\/audit$/);
    await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible();
    // Written by the "creating a user from the admin panel" test above; the file is serial, so it has run.
    await expect(page.getByText('user.create').first()).toBeVisible();
    await expect(page.getByText(process.env.BOOTSTRAP_ADMIN_EMAIL ?? 'admin@example.com').first()).toBeVisible();
  });

  test('editing and deleting a user, with confirmation before delete', async () => {
    await page.getByRole('link', { name: 'Users' }).click();
    const row = page.locator('tr', { hasText: email });
    await row.getByRole('link', { name: email }).click();
    await expect(page.getByRole('button', { name: 'Delete user' })).toBeVisible();

    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Delete user' }).click();
    await expect(page).toHaveURL(/\/en\/admin\/users$/);
    await expect(page.getByText(email)).not.toBeVisible();
  });

  test('the deletion is itself in the audit log', async () => {
    await page.getByRole('link', { name: 'Audit log' }).click();
    await expect(page.getByText('user.delete').first()).toBeVisible();
  });
});
