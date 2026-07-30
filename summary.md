# Product Overview

A private, account-based web application. Every feature except account creation and sign-in
requires an authenticated session. Each user's data is private to their own account — no user
can see another user's data, except administrators managing accounts (not user content).

## Accounts & Authentication

- Visitors can create an account with an email and a password (minimum 8 characters, enforced
  both in the form and on the server).
- Registered users log in with email + password.
- A "quick sign-in" mode exists for non-production environments only: a user can sign in by
  entering just an email, no password, and an account is created/logged into automatically for
  that email. This mode is fully disabled in production.
- Failed login/signup/quick-sign-in attempts are rate-limited per email: after 5 failures within
  a 15-minute window, further attempts are rejected until the window passes.
- A logged-in session persists for 7 days and can be ended explicitly via "log out", which
  invalidates the session immediately.
- One admin account is pre-provisioned in non-production environments so there is always at
  least one administrator available; this auto-provisioning is disabled in production.

## Roles

- **Regular user**: manages only their own data (see Entries and Page2 below), can change the
  visual theme, can log out.
- **Administrator**: everything a regular user can do, plus full user management (see below).
  Regular users cannot see or access user-management features.

## Theming

- Users can choose among 5 visual themes (Light, Dark, Cupcake, Synthwave, Corporate) and the
  chosen theme applies to the whole application immediately.

## Feature: Entries

- A personal page containing three independent, separately-labeled lists ("Note A", "Note B",
  "Note C").
- Each list accepts a single free-text value; submitting adds it to that list's history, newest
  first.
- Submitting an empty/blank value is rejected (no-op).
- Every entry records which of the three lists it belongs to, its text value, and its creation
  time. Entries are private to the owning user.

## Feature: Page2 (named list of names/nicknames)

- A personal page containing one list of paired values: a "name" and a "nickname".
- Submitting requires both a name and a nickname; either being empty blocks submission.
- Newly added items appear at the top of the list, displayed as "Name (Nickname)".
- Each item records name, nickname, and creation time. Items are private to the owning user.

## Feature: User Management (administrators only)

- **List users**: view all accounts with email, admin status, and creation date.
- **Create user**: administrators can create a new account by supplying email, password (same
  8-character minimum as signup), and whether the new account is an administrator.
- **Edit user**: administrators can change a user's email, admin status, and optionally reset
  their password (leaving the password field blank keeps the existing password).
- **Delete user**: administrators can permanently remove a user account; this requires an
  explicit confirmation step before it takes effect.
- **Self-protection rule**: an administrator cannot remove their own admin privileges and cannot
  delete their own account (enforced independent of the interface, i.e. attempting it directly
  is also rejected).
- Attempting to view a user that no longer exists shows a "not found" state.
- Duplicate email, invalid email format, and weak passwords are rejected with a specific error
  describing the problem.

## Cross-cutting behavior

- Any page that requires a session redirects an unauthenticated visitor to sign-in.
- Any admin-only page denies access to a signed-in non-admin, with a message explaining they are
  signed in but lack admin rights.
- Error states (failed load, failed save, validation errors) are surfaced to the user as
  descriptive inline messages, not silent failures.
