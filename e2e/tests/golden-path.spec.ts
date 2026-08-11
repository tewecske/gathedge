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
  // The daisyUI swap stacks its icons on top of the checkbox in one grid cell, so clicking the
  // input directly fails Playwright's hit-target check — click the label, which is what a user hits.
  await expect(page.getByRole('checkbox', { name: /Switch to/ })).toBeAttached();
  await page.locator('label.swap').click();
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
  const pairsTable = page.locator('table').filter({ has: page.locator('th', { hasText: 'Source' }) });

  await page.getByRole('button', { name: 'Add' }).click(); // blank submit: no-op
  await expect(pairsTable.locator('tbody tr')).toHaveCount(0);

  await page.locator('input[placeholder="Source"]').fill('source-value');
  await page.locator('input[placeholder="Target"]').fill('target-value');
  await page.getByRole('button', { name: 'Add' }).click();

  await expect(pairsTable.locator('tbody tr', { hasText: 'source-value' })).toBeVisible();
});

// Members (the list and the invite form) live on their own route, reached from the
// group submenu — they were split out of the group overview and are not on it.
test('inviting a member is accepted by the server (link delivery is out of e2e reach)', async () => {
  await page.getByRole('link', { name: 'Members' }).click();
  await expect(page).toHaveURL(/\/en\/groups\/\d+\/members$/);
  await page.locator('input[placeholder="Email to invite"]').fill(`invitee-${unique}@example.com`);
  await page.getByRole('button', { name: 'Invite' }).click();
  await expect(page.getByText(/Invited /)).toBeVisible();
});

test('the members list survives a page refresh', async () => {
  await page.reload();
  // Must include the creator (self) — this is the "empty members" bug.
  await expect(page.getByText(email)).toBeVisible();
});

test('group detail (pairs, add-pair form) survives a page refresh', async () => {
  await page.getByRole('link', { name: 'Overview' }).click();
  await page.reload();
  // Add-pair form is only rendered once the group (with myRole) has loaded.
  await expect(page.locator('input[placeholder="Source"]')).toBeVisible();
  await expect(page.locator('input[placeholder="Target"]')).toBeVisible();
  await expect(page.locator('table tbody tr', { hasText: 'source-value' })).toBeVisible();
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
    // The forbidden page the previous test lands on renders outside the app shell, so
    // there is no navbar on it to reach the account menu through: go somewhere there is
    // one first. Signing out has to happen before /en/sign-in, which is RequireAnon and
    // would bounce a still-signed-in visitor straight back to the board.
    await page.goto('/en/');
    await page.getByRole('button', { name: 'Account menu' }).click();
    await page.getByRole('button', { name: 'Log out' }).click();
    await expect(page).toHaveURL(/\/en\/sign-in$/);
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
    // `exact`, because the sortable "Created" column heading is a button whose name starts with the same word.
    await page.getByRole('button', { name: 'Create', exact: true }).click();
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
    // Scoped to the table: the same wording is an `<option>` in the action filter above it, and an
    // option inside a closed `<select>` is hidden. The badge shows the worded label rather than the
    // stored `user.create` code — only the option's `value` stays the code, since that is what the
    // filter request carries.
    const rows = page.locator('table tbody');
    await expect(rows.getByText('Create user').first()).toBeVisible();
    await expect(rows.getByText(process.env.BOOTSTRAP_ADMIN_EMAIL ?? 'admin@example.com').first()).toBeVisible();
  });

  // The listing state lives in the URL, and only a real browser can say whether it survives the trip: the address has
  // to keep what a link put in it, the numbers in it have to be the numbers on the buttons, and the back button has to
  // walk back through the listing rather than off it. All three were wrong, and none of the three is visible to a
  // jsdom spec, which has no address bar and no history.
  test('a listing keeps its filter in the URL, numbers its pages from one, and steps back', async () => {
    // A shared or bookmarked filter link: it arrives filtered, and stays that way. It used to clear itself as the page
    // mounted, taking `?q=` out of the address with it.
    await page.goto(`/en/admin/users?q=${encodeURIComponent(email)}`);
    await expect(page.locator('input[type=search]')).toHaveValue(email);
    await expect(page.locator('table tbody tr')).toHaveCount(1);
    expect(new URL(page.url()).searchParams.get('q')).toBe(email);

    // One row per page, so there is a second page to reach whatever the dev database holds.
    await page.goto('/en/admin/users?size=1');
    const pageButton = (number: string) => page.getByRole('button', { name: number, exact: true });
    await expect(pageButton('1')).toHaveAttribute('aria-current', 'page');

    await pageButton('2').click();
    // One-based: the second page is `page=2`, not `page=1`.
    await expect(page).toHaveURL(/[?&]page=2(&|$)/);
    await expect(pageButton('2')).toHaveAttribute('aria-current', 'page');

    await page.goBack();
    await expect(page).not.toHaveURL(/page=/);
    await expect(pageButton('1')).toHaveAttribute('aria-current', 'page');

    // The first search is a place worth returning to, so it gets a history entry of its own. Typing the term out
    // further replaces that entry rather than adding one per pause, which is why one press is enough here.
    await page.locator('input[type=search]').fill('e2e-');
    await expect(page).toHaveURL(/[?&]q=e2e-/);
    await page.goBack();
    await expect(page).not.toHaveURL(/q=/);
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
    await expect(page.locator('table tbody').getByText('Delete user').first()).toBeVisible();
  });
});
