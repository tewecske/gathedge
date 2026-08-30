import { defineConfig, devices } from '@playwright/test';

// Assumes the full stack is already running:
//   docker compose up -d        (Postgres)
//   sbt '~backend/reStart'      (or `npm run dev` from repo root, which starts all three)
//   sbt '~frontend/fastLinkJS'
//   npm --prefix web run dev
export default defineConfig({
  testDir: './tests',
  // A single reachability check for that stack — one clear error instead of 11 opaque timeouts.
  globalSetup: './global-setup.ts',
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    // Pinned so the app's language never depends on the machine running the suite: with no locale
    // prefix in the URL, index.html's boot script picks one from navigator.languages and redirects.
    // Every path below names /en explicitly anyway; this keeps a stray bare URL deterministic too.
    locale: 'en-US',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
