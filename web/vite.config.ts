import { defineConfig } from 'vite'
import tailwindcss from '@tailwindcss/vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'

export default defineConfig({
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
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
