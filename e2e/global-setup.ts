// One reachability check before the suite runs, so a stack that is not up produces a single clear
// message instead of every first-mount test dying on an opaque `net::ERR_ABORTED` / heading timeout.
//
// This does not start anything — see playwright.config.ts for what has to be running.

async function reachable(url: string): Promise<boolean> {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 3000);
    await fetch(url, { signal: controller.signal });
    clearTimeout(timer);
    return true;
  } catch {
    return false;
  }
}

export default async function globalSetup(): Promise<void> {
  // Both follow PLAYWRIGHT_BASE_URL, so a worktree's own stack is checked rather than the default one.
  // The backend is reached through Vite's own /api proxy, which is how every test reaches it too.
  const vite = process.env.PLAYWRIGHT_BASE_URL ?? 'http://localhost:5173';
  const backend = `${vite}/api/auth/providers`;

  const [viteUp, backendUp] = await Promise.all([reachable(vite), reachable(backend)]);

  if (!viteUp || !backendUp) {
    const missing = [!viteUp && `Vite dev server (${vite})`, !backendUp && `backend (via ${vite}/api)`].filter(Boolean);
    throw new Error(
      `e2e stack not reachable: ${missing.join(', ')}.\n` +
        'Start it first (see e2e/playwright.config.ts):\n' +
        '  docker compose up -d postgres\n' +
        '  npm run dev            # backend + frontend fastLinkJS + vite\n' +
        '  sbt "backend/runMain gathedge.backend.tools.DictionaryImport --seed"   # vocabulary/tag specs',
    );
  }
}
