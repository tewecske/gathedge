import { defineConfig, loadEnv } from 'vite'
import tailwindcss from '@tailwindcss/vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'

// Read from the repo-root .env (not web/.env — there isn't one), the same file `reStart / envVars`
// feeds the backend from. This is what lets each git worktree pick its own dev ports in one place:
// VITE_PORT is Vite's own listen port, SERVER_PORT is the backend port the /api proxy targets.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, new URL('..', import.meta.url).pathname, '')
  const vitePort = Number(env.VITE_PORT ?? 5173)
  const serverPort = Number(env.SERVER_PORT ?? 8080)

  return {
    resolve: {
      alias: {
        // The linked Scala.js output lives outside `web/` (under `modules/frontend/target`, per the
        // `cwd: '..'` below), so the bundler's default node_modules resolution — which walks up from the
        // *importing file's* own directory — never reaches `web/node_modules` for a bare import like
        // `qrcode` and fails to resolve it. Aliasing pins it to the copy actually installed here. `.pathname`
        // rather than Node's `url.fileURLToPath`, so this needs no `@types/node` for `npm run typecheck`.
        qrcode: new URL('./node_modules/qrcode', import.meta.url).pathname,
        'tesseract.js': new URL('./node_modules/tesseract.js', import.meta.url).pathname,
      },
    },
    plugins: [
      tailwindcss(),
      scalaJSPlugin({ cwd: '..', projectID: 'frontend' }),
    ],
    build: {
      rollupOptions: {
        input: {
          main: 'index.html',
        },
      },
    },
    server: {
      port: vitePort,
      proxy: {
        '/api': {
          target: `http://localhost:${serverPort}`,
          changeOrigin: true,
        },
      },
    },
  }
})
