# Product Specification

## What this is

A private, account-based web application with three parts:

- a personal task board, private to each account;
- shared group workspaces, private to the members of each group;
- account administration, available only to administrators.

Everything except signing up, signing in, viewing an invitation and confirming an email address
requires being signed in. No user can see another user's personal data. Administrators manage
*accounts*, not the content those accounts own: an administrator has no way to read another
user's task board or a group they are not a member of.

Each rule below is written as one testable statement. Where a rule exists for a non-obvious
reason, the reason follows on the same line; those reasons are the most valuable part of this
document for anyone rebuilding the application.

## Concepts

**User.** An account identified by an email address. May have a password, may have one or more
linked social sign-in accounts, may have both. Has a chosen visual theme, a creation date, an
administrator flag, and a record of whether its email address has been confirmed.

**Session.** A signed-in period belonging to one user. Lasts 7 days, and can be ended earlier by
signing out or by an administrator resetting that user's password.

**Group.** A named workspace owned by its members. Every member holds exactly one role:
administrator, read-write, or read-only. A group always has at least one administrator.

**Invitation.** An offer to join one group in one role, addressed to one email address, delivered
as a link. Valid for 7 days and usable once.

**Task item.** A short piece of text belonging to one user, in one of three states: To Do,
In Progress, Done.

**Group entry.** A pair of values, a source and a target, belonging to one group, recording who
added it and when.

## Screens

| Screen | Who can see it |
| --- | --- |
| Task board (home) | signed-in users |
| Groups list | signed-in users |
| Group entries | members of that group |
| Group members | members of that group |
| Account settings | signed-in users |
| Administrator user list | administrators |
| Administrator user detail | administrators |
| Sign in | signed-out visitors |
| Sign up | signed-out visitors |
| Check your inbox | anyone |
| Confirm email address | anyone |
| Accept invitation | anyone (accepting needs an account) |
| Access denied | anyone |
| Not found | anyone |

## Accounts and sign-in

Visitors create an account with an email address and a password, or with a social account.
Returning users sign in the same two ways.

- A password is at least 8 and at most 72 characters. An email address is at most 255 characters
  and must look like an address.
- An email address identifies exactly one account. A second sign-up with the same address is
  rejected as a duplicate.
- Email addresses are compared without regard to case or surrounding spaces.
- A wrong password and an unknown address produce the same answer, so the sign-in form cannot be
  used to discover which addresses have accounts.
- After 5 failed attempts within 15 minutes, further attempts are refused until the window
  passes, and the user is told that this is why. The count is kept per email address *and* per
  origin of the request, so neither spraying one password across many accounts nor hammering one
  account is cheaper than the other.
- A successful sign-in clears that address's failure count.
- Signing out ends the session immediately, and signing out when not signed in is not an error.
- A session lasts 7 days.
- Any screen that needs a session sends a signed-out visitor to sign-in. Any administrator-only
  screen tells a signed-in non-administrator that they are signed in but lack the rights, rather
  than pretending the screen does not exist.
- Sign-up and sign-in screens redirect a user who is already signed in.

## Email confirmation

A confirmation link is sent whenever an account is created with a password, and again on request.
It is always redeemable. Whether an unconfirmed account may *sign in* is a deployment choice.

- The link is valid for 24 hours and can be used once.
- An unknown, spent or expired link produces one and the same answer, so links cannot be guessed
  by probing.
- Where confirmation is required, sign-in is refused for an unconfirmed account only *after* the
  password has been checked, so the refusal never reveals that an address has an account.
- Where confirmation is required, signing up does not sign the new user in. The sign-up screen
  says so and points at the inbox.
- A user refused for this reason is offered a fresh link on the spot.
- Requesting a link answers the same way for an unknown address, an already confirmed one, and a
  successful send. The wording on every screen that offers this must stay equally non-committal.
- Link requests are rate limited on their own budget, so asking for a link never uses up a user's
  sign-in attempts and the reverse.
- An account created by a social sign-in starts confirmed only if that provider vouches for the
  address. Accounts created by an administrator, and the starter administrator account, start
  confirmed.
- Expired and spent links are discarded periodically.

## Social sign-in and account linking

Users may sign in with a Google or a Microsoft account, and may attach either to an existing
account from the settings screen.

- Only the provider's own permanent account identifier decides which account a social sign-in
  enters. The email address a provider reports is display information and never grants access.
- A social sign-in whose identifier is unrecognised, for an address that already has an account,
  is **refused** rather than merged into it. Any provider that lets a user assert an address they
  do not own would otherwise be a way to take over accounts. The recovery path is to sign in the
  normal way and attach the provider from settings, which makes that screen a hard requirement
  and not a convenience.
- A social sign-in whose identifier is unrecognised, for a free address, creates an account.
- The same identifier signing in again always lands in the same account, never a second one.
- Attaching a provider that is already attached to this account is refused.
- Attaching a provider confirms the account's email address only when the provider vouches for
  the *same* address.
- An account may not remove its last remaining way to sign in. Unlinking the only social account
  of an account that has no password is refused, otherwise a user could lock themselves out
  permanently in one click. The screen also disables the button, but the server check is the real
  one.
- Removing a provider that is not attached is an error, not a silent success.
- A provider the deployment has not been configured for is not offered anywhere and cannot be
  reached by typing its address by hand; it behaves exactly like a provider that does not exist.
- Attaching a provider requires being signed in; arriving back from the provider without a
  session returns to settings with an explanation rather than an error page.
- Failures on the way back from a provider return the user to sign-in or settings with a readable
  message, never a raw fault.

## Account settings

- Shows which social accounts are attached, whether a password is set, and which providers this
  deployment offers.
- A user with no password can set one; a user with a password can change it by supplying the
  current one.
- A wrong current password is reported against that field, and a too-weak new one against its own
  field, so each message appears under the input it concerns.
- After setting a password, signing in with it works immediately.

## Theme

- Each user chooses Light or Dark. The choice applies to the whole application immediately and is
  stored on the account, so it follows the user to another browser.

## Task board

The home screen. Three lists side by side: To Do, In Progress, Done.

- Submitting text adds an item to To Do.
- Clicking an item advances it: To Do to In Progress to Done, and from Done back to To Do.
- Blank or whitespace-only text is rejected and adds nothing.
- Text is at most 2000 characters; longer text is a field error, not a failure.
- Every item records its text, its state, and when it was created.
- Items are private to their owner. Acting on someone else's item is answered as if it does not
  exist, so ownership cannot be probed.
- The list is loaded from the server, not held only in the page: a refresh shows the same items.

## Groups

- Any user can create a group, and becomes its administrator.
- A blank group name is rejected. A name is at most 255 characters.
- The groups screen lists the groups the user belongs to, with their role in each.
- A group may have several administrators. Any of them can invite, remove members, change roles,
  and delete the group.
- Read-write and read-only members can do neither of those things.
- A group must always keep at least one administrator. Removing or demoting the last one is
  refused; promoting somebody else first is the only way for a sole administrator to leave.
- Deleting a group removes its entries, members and outstanding invitations with it.
- Removing a member revokes their access to the group and its content at once.
- A signed-in user who is not a member is told they may not see the group, not that it is
  missing. A group that genuinely does not exist is reported as missing. The distinction is
  deliberate: a member who has just been removed should learn that, not be misled into thinking
  the group was deleted.

## Group entries

Each group has one table of paired values, a source and a target.

- Adding an entry requires both fields; either one blank blocks the submission and reports the
  empty field.
- Each field is at most 2000 characters.
- Read-only members can view entries but cannot add them.
- A new entry appears in the table straight away.
- Every entry records its group, its source and target, who added it, and when.
- Entries are private to their group. A non-member can neither read nor add.
- The table is loaded from the server: a refresh shows the same entries.
- Deleting the user who added an entry does not delete the entry's group or the other entries.

## Invitations

- A group administrator invites a person by email address, choosing the role they will hold.
- The invited address receives a link naming the group.
- Anyone holding the link can see who the invitation is for, which group it is for, which role it
  grants, and whether it has expired or already been used, without signing in. Nothing else about
  the group is revealed.
- Accepting requires being signed in. A visitor without an account is asked to create one first.
- An invitation can only be accepted by the address it was sent to. Accepting it while signed in
  as anybody else is refused, so a forwarded link grants nothing.
- An invitation can be accepted once, and expires after 7 days. Unknown, spent and expired links
  are all answered the same way.
- Accepting puts the user in the group with the role the invitation named, and takes them to it.

## Administrator user management

Available only to administrators, and only over accounts.

- List every account with its email address, administrator status, and creation date.
- Create an account with an email address, a password, and whether it is an administrator. The
  same address and password rules as sign-up apply, and a duplicate address is refused.
- Edit an account's email address and administrator status. Leaving the password field blank
  keeps the existing password; filling it resets the password.
- Resetting a user's password ends all of that user's sessions, so a compromised session does not
  outlive the reset.
- A profile change and a password reset submitted together either both take effect or neither
  does.
- Delete an account, behind an explicit confirmation step. Deleting an account also removes the
  group entries it created and the invitations it sent, and it must not leave anything behind
  that fails when read.
- An administrator cannot remove their own administrator status and cannot delete their own
  account. Both are refused by the server as well as hidden in the screen, so bypassing the
  interface does not bypass the rule.
- Opening a user who no longer exists shows a "not found" state rather than an error.
- Duplicate address, malformed address and too-weak password are each refused with a message
  naming the actual problem.
- Every administrator action on an account is recorded in the security log with the administrator
  who performed it.

## Rules that apply everywhere

- Every rule in this document is enforced by the server. The interface hides what a user may not
  do, but hiding is never the enforcement.
- A request for something that exists but is not yours is answered "not allowed"; a request for
  something that does not exist is answered "not found". Choose deliberately per case: the first
  reveals existence, the second hides it.
- Failures are shown to the user as readable inline messages next to what failed. Nothing fails
  silently, and no internal fault text ever reaches the user.
- Field-level validation errors appear under the field they concern. A form shows no errors
  before its first submission, however invalid its contents, and fixing one field after a failed
  submission clears that field's error alone.
- Input that exceeds a stored length limit is a field error, never a failure.
- Lists are always re-read from the server rather than kept only in the page, so a refresh, a
  second tab and a second device agree.
- A session that expires under an open page results in the user being sent to sign-in, not in a
  broken page.
- Requests that change data are only accepted when they demonstrably come from the application's
  own screens, so another site cannot make a signed-in user's browser act on their behalf.

## Limits

| Thing | Limit |
| --- | --- |
| Password | 8 to 72 characters |
| Email address | 255 characters |
| Group name | 255 characters |
| Task text, entry source, entry target | 2000 characters each |
| Session | 7 days |
| Invitation | 7 days, single use |
| Email confirmation link | 24 hours, single use |
| Failed sign-in attempts | 5 per 15 minutes, per address and per origin |

## Non-functional requirements

- Store passwords only in an irreversible form, using a function designed for passwords. Never
  log, display or return one.
- Serve the application over an encrypted connection outside development, and follow recognised
  web application security guidance.
- Keep a separate security log for security-relevant events: failed sign-ins, rate-limit trips,
  denied administrator access, and administrator actions on accounts. Keep it apart from general
  application logging so it can be watched and retained on its own terms.
- Never write a secret to any log: no password, no password hash, no session identifier, no
  invitation or confirmation link, no social account identifier, and no email address.
- Log ordinary application events too: user actions, errors, lifecycle events.
- Publish a machine-readable description of the application's own interface, kept in step with
  what the application actually accepts and answers, and generated from the same definitions
  rather than maintained separately.
- Automated tests at three levels: unit tests for rules, integration tests against a real data
  store, and end-to-end tests driving the real screens. Referential rules (what a deletion takes
  with it) must be tested against the real data store, since a substitute may not enforce them.
- Validation rules are stated once and used by both the screens and the server, so the two cannot
  disagree.
- Keep the structure simple, modular, and easy to extend: clear separation between screens,
  request handling, rules, and storage.

## Deployment-time choices

These change behavior and should be decisions, not accidents.

- **Does an unconfirmed address block sign-in?** Off by default, so a deployment without a mail
  server still works. Turning it on requires a real mail server.
- **Which social providers are offered?** Each is configured independently. An unconfigured one
  is absent from every screen and unreachable by hand.
- **Is a starter administrator account created?** Yes outside production, so there is always one
  administrator to sign in as. Never in production.
- **Is outgoing mail really sent?** With no mail server configured, messages are recorded instead
  of sent, which is what makes a local setup usable.
- Production refuses to start on a configuration that is unsafe: a default password, an
  unencrypted public address, or a confirmation requirement with no way to send mail. Fail at
  startup, not at the first user.
