# Product Overview

A private, account-based web application. Every feature except account creation and sign-in
requires an authenticated session. Each user's data is private to their own account — no user
can see another user's data, except administrators managing accounts (not user content).

## Accounts & Authentication

- Visitors can create an account with an email and a password (minimum 8 characters, enforced
  both in the form and on the server).
- Registered users log in with email + password.
- An OAuth2-based "quick sign-in" option is available for users with a Google account; this bypasses
  the password requirement and uses the Google account as the identity provider. 
- Failed login/signup/quick-sign-in attempts are rate-limited per email: after 5 failures within
  a 15-minute window, further attempts are rejected until the window passes.
- A logged-in session persists for 7 days and can be ended explicitly via "log out", which
  invalidates the session immediately.
- One admin account is pre-provisioned in non-production environments so there is always at
  least one administrator available; this auto-provisioning is disabled in production.

## Roles

- **Regular user**: manages only their own data (see Entries and Page2 below), can change the
  visual theme, can log out.
- **Group admin**: can invite and remove users from the group.
- **Administrator**: everything a regular user can do, plus full user management (see below).
  Regular users cannot see or access user-management features.

## Theming

- Users can choose from visual themes (Light, Dark) and the
  chosen theme applies to the whole application immediately and saved in user profile.

## Feature: TODO list

- A simple TODO list like page with three separate lists: "To Do", "In Progress", and "Done".
- Submit to TODO adds a new item to the "To Do" list; items can be moved between lists by clicking
  on them.
- Submitting an empty/blank value is rejected (no-op).
- Every entry records its text value, and its creation
  time. Entries are private to the owning user.

## Feature: Group Management

- Any user can create a group and become its administrator. Groups are private to their members.
- There can be multiple administrators in a group; any administrator can invite or remove users.
- There has to be at least one administrator in a group. The last administrator cannot remove themselves
  from the group unless they first promote another user to administrator.
- Group administrators can invite other users to join the group by email. Invited users receive
  an email with a link to accept the invitation. Invited users must have an account to join the group;
  if they do not have an account, they are prompted to create one first.
- Users in the group can be group administrators, read only users or read and write users.
- Group administrators can remove users from the group. Removing a user revokes their access to
  the group and its data immediately.
- Group administrator can delete the group.

## Feature: Page for groups
- A group page containing one table of paired values: a "source" and a "target".
- Submitting requires both a source and a target; either being empty blocks submission.
- Newly added items appear in the table.
- Each item records group, source, target, and creation user and time. Items are private to the owning group.

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

## Other Notes
Security is important. Strongly encrypt passwords, use HTTPS, and follow OWASP/CWE best practices for web application security.
Add logging and monitoring for security events, such as failed login attempts, suspicious activity.
Add general logging for application events, such as user actions, errors, and system events.
Add tests for backend and front-end functionality, including unit tests, integration tests, and end-to-end tests.
Keep the code simple and easily extendable, with clear separation of concerns and modular design.


