-- The account's preferred language, as an ISO 639-1 code matching shared's `Locale.languageCode`.
--
-- The URL prefix (/en, /hu) is what actually decides the language a page renders in; this column
-- exists for the two things a URL cannot reach. First, transactional email: the verification and
-- invitation messages are composed server-side with no browser in the room, so without a stored
-- preference they could only ever be English. Second, a first visit from a new browser, where it
-- seeds the redirect for a prefix-less URL — but only then, since an explicit prefix always wins.
ALTER TABLE users ADD COLUMN locale VARCHAR(10) NOT NULL DEFAULT 'en';
