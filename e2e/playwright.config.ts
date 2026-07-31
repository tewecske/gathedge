import { defineConfig, devices } from '@playwright/test';

// Assumes the full stack is already running:
//   docker compose up -d        (Postgres)
//   sbt '~backend/reStart'      (or `npm run dev` from repo root, which starts all three)
//   sbt '~frontend/fastLinkJS'
//   npm --prefix web run dev
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
