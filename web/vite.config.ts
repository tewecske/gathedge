import { defineConfig } from 'vite'
import tailwindcss from '@tailwindcss/vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'

export default defineConfig({
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
