// Copies the `tesseract.js`/`tesseract.js-core` files `ImageOcr` needs to be self-hosted (see its scaladoc for why:
// serving them ourselves, rather than pointing at `tesseract.js`'s CDN default, keeps a bulk-upload image scan
// working offline-first and off a third-party host). Run via the `predev`/`prebuild` npm hooks, so it is always
// current before either dev server or `vite build` starts.
//
// Only the `-lstm` core variants are copied: `ImageOcr` always asks for OEM 1 (`LSTM_ONLY`), `tesseract.js`'s own
// recommended default, and its browser loader (`getCore.js`) picks between the plain/`simd`/`relaxedsimd` `-lstm`
// files itself via runtime feature detection — there is no need to also ship the non-LSTM variants this app never
// requests.
import { existsSync, mkdirSync, copyFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const webRoot   = dirname(dirname(fileURLToPath(import.meta.url)))
const targetDir = join(webRoot, 'public', 'tesseract', 'core')

mkdirSync(targetDir, { recursive: true })

const workerSrc = join(webRoot, 'node_modules', 'tesseract.js', 'dist', 'worker.min.js')
copyFileSync(workerSrc, join(targetDir, 'worker.min.js'))

const coreDir  = join(webRoot, 'node_modules', 'tesseract.js-core')
const variants = ['tesseract-core-lstm', 'tesseract-core-simd-lstm', 'tesseract-core-relaxedsimd-lstm']
for (const variant of variants) {
  for (const ext of ['.wasm', '.wasm.js']) {
    const file = `${variant}${ext}`
    copyFileSync(join(coreDir, file), join(targetDir, file))
  }
}

if (!existsSync(join(webRoot, 'public', 'tesseract', 'lang', 'eng.traineddata.gz'))) {
  console.warn(
    'warning: web/public/tesseract/lang/*.traineddata.gz is missing — bulk-upload image scanning will fail until ' +
      'those files are added (see web/public/tesseract/lang/README.md).'
  )
}
