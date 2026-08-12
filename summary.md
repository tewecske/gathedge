# Product Specification

## What this is

The account-and-administration half of a private, account-based web application — the part that is
the same in every such application, with the part that makes one specific left out.

It provides two things: a way for a person to have an account (sign up, confirm an address, sign in
with a password or a social account, choose a language and a theme), and a way for an administrator
to look after those accounts (find one, diagnose why it cannot sign in, see what administrators have
done, and see what the deployment is holding).

Everything except signing up, signing in and confirming an email address requires being signed in.
Administrators manage *accounts*, not the content those accounts own — a rule that matters most for
whatever a project built on this adds: the administrator screens are designed so that no amount of
new data becomes readable through them, only countable.

Each rule below is written as one testable statement. Where a rule exists for a non-obvious
reason, the reason follows on the same line; those reasons are the most valuable part of this
document for anyone rebuilding the application.

The home screen is a placeholder. So is this sentence's absence of a feature: a project starting
from here replaces both.

## Concepts

**User.** An account identified by an email address. May have a password, may have one or more
linked social sign-in accounts, may have both. Has a chosen visual theme, a creation date, an
administrator flag, and a record of whether its email address has been confirmed.

**Session.** A signed-in period belonging to one user. Lasts 7 days, and can be ended earlier by
signing out or by an administrator resetting that user's password.

## Screens

| Screen | Who can see it |
| --- | --- |
| Home (a placeholder) | signed-in users |
| Account settings | signed-in users |
| Administrator user list | administrators |
| Administrator user detail | administrators |
| Administrator audit log | administrators |
| Administrator system overview | administrators |
| Sign in | signed-out visitors |
| Sign up | signed-out visitors |
| Check your inbox | anyone |
| Confirm email address | anyone |
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

## Home

A placeholder: a card greeting the signed-in user, and nothing else. It exists so the router, the
shell and the sign-in guard have somewhere to point, and it is the first screen a project built on
this replaces.

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
- Delete an account, behind an explicit confirmation step. Deleting an account removes everything
  belonging to it — its sessions, its linked social accounts, its outstanding confirmation links,
  and whatever a later feature stores on its behalf — and must not leave anything behind that
  fails when read. The two exceptions are deliberate and below.
- An administrator cannot remove their own administrator status and cannot delete their own
  account. Both are refused by the server as well as hidden in the screen, so bypassing the
  interface does not bypass the rule.
- Opening a user who no longer exists shows a "not found" state rather than an error.
- Duplicate address, malformed address and too-weak password are each refused with a message
  naming the actual problem.
- Every administrator action on an account is recorded in the security log with the administrator
  who performed it, and in a stored audit trail the interface can show.

### Helping a user who cannot get in

The account list says, for every account, whether its address is confirmed and whether sign-in is
currently blocked. Opening an account adds four things, none of which is data the account owns.

- **Confirmation.** Whether the address is confirmed and when; whether a link is outstanding, and
  whether it has been used or has expired. An administrator can send a fresh link, or confirm the
  address outright for someone who cannot receive one. Neither is subject to the limits the
  self-service request is: the requester has already been identified, so there is nobody to conceal
  the account's existence from and no budget to spend.
- **Sign-in history.** Every recorded attempt against that address, successful or not, with when it
  happened, what the outcome was, and where it came from. An attempt against an address with no
  account is recorded too, and stays recorded after the account is deleted. This survives a restart;
  the block itself does not.
- **Lockout.** Whether the account is blocked, how many attempts of the allowed number are counted,
  and how long until it clears itself. An administrator can clear it, which clears every dimension
  of it — the address and every origin it was recently attempted from — because a block on any one
  of them keeps the user out.
- **Sessions and social accounts.** How many sessions are live and when each began and expires, with
  one action that ends all of them. Which providers are attached, when, and the address each
  reported, with an action to detach one. Detaching the last way an account can sign in is refused,
  the same as it is for the account's own settings screen; an administrator is not exempt, because
  the account it would lock out is not theirs.

No session identifier is shown, for the same reason none is logged: it is the credential itself.
That is why sessions are ended all at once rather than one at a time.

### The audit trail

Every administrator action is listed, most recent first, filterable by action and by administrator,
and pageable backwards. Each entry says when, who, what, which account it concerned, and where the
request came from. The address of the administrator is recorded at the time of the action, so an
entry still names who acted after that administrator's own account is deleted — which is the case an
audit trail exists for. Deleting an account leaves its entries and its sign-in history in place.

### System overview

One screen for facts about the deployment rather than about any account.

- What it is configured to do: environment, public address, whether confirmation is required,
  whether session cookies are secure, which social providers are offered, whether mail is really
  being sent and through which relay, every time limit and the sign-in attempt limit.
- **No configured secret is shown.** Every credential-bearing setting is reduced to "configured" or
  omitted, and the database address has any credentials stripped out of it.
- What it is doing: version, uptime, memory, threads, which schema versions are applied, and when
  each background maintenance job last ran and whether it failed. A job that has not run in far
  longer than its interval is the signal that something is wrong, and there was previously no way
  to see it.
- How much it is holding: a count per kind of record. Counts only, and this is the rule a new
  feature has to keep: a table may hold data no administrator is allowed to read, so it is counted
  and never read. Numbers that indicate a problem are marked: unconfirmed
  addresses, expired records the cleanup has not removed, failed sign-ins in the last day, accounts
  currently locked out.
- Two maintenance actions, both confirmed first: run the periodic cleanup now, and release every
  current sign-in block. Releasing blocks is deliberately separate from the cleanup, so routine
  housekeeping cannot let an attack back in.

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
| Session | 7 days |
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
- Store the same administrator actions where the interface can query them, written by whatever
  writes the log line, so the two cannot come to describe different sets of events. Store sign-in
  attempts likewise. Neither store may be able to fail the action it records: a full disk must not
  turn a correct password into a failed sign-in, nor a completed administrative action into an
  error saying it did not happen.
- Never write a secret to any log: no password, no password hash, no session identifier, no
  confirmation link, no social account identifier, and no email address. This covers the request
  log as well as the application's own lines — a credential that travels inside a URL is still a
  credential.
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
