import fs from 'node:fs'
import { SourceMapConsumer } from 'source-map'

const jsPath = '../modules/frontend/target/scala-3.8.4/frontend-opt/main.js'
const mapPath = jsPath + '.map'

const jsLines = fs.readFileSync(jsPath, 'utf8').split('\n')
const rawMap = JSON.parse(fs.readFileSync(mapPath, 'utf8'))

const consumer = await new SourceMapConsumer(rawMap)

// Collect all mappings, sorted by generated position, then attribute the gap
// between one mapping and the next generated position to the earlier mapping's source.
const mappings = []
consumer.eachMapping((m) => {
  mappings.push({ line: m.generatedLine, col: m.generatedColumn, source: m.source })
})
mappings.sort((a, b) => (a.line - b.line) || (a.col - b.col))

const bytesByLibrary = new Map()

function libraryOf(source) {
  if (!source) return '(no source / synthetic)'
  if (source.includes('/modules/shared/')) return 'gathedge:shared'
  if (source.includes('/modules/frontend/')) return 'gathedge:frontend'
  let m = source.match(/^file:\/\/\/home\/runner\/work\/([^/]+)\//)
  if (m) return m[1]
  m = source.match(/^https:\/\/raw\.githubusercontent\.com\/([^/]+)\/([^/]+)\//)
  if (m) return `${m[1]}/${m[2]}`
  return source.slice(0, 80)
}

for (let i = 0; i < mappings.length; i++) {
  const cur = mappings[i]
  const next = mappings[i + 1]
  const lineLen = (jsLines[cur.line - 1] ?? '').length + 1 // +1 for the newline
  let span
  if (next && next.line === cur.line) {
    span = Math.max(0, next.col - cur.col)
  } else {
    span = Math.max(0, lineLen - cur.col)
  }
  const lib = libraryOf(cur.source)
  bytesByLibrary.set(lib, (bytesByLibrary.get(lib) ?? 0) + span)
}

consumer.destroy()

const totalMapped = [...bytesByLibrary.values()].reduce((a, b) => a + b, 0)
const totalFileBytes = fs.statSync(jsPath).size
console.log(`total file bytes: ${totalFileBytes}`)
console.log(`total attributed via mappings: ${totalMapped}`)
console.log('')

const rows = [...bytesByLibrary.entries()].sort((a, b) => b[1] - a[1])
for (const [lib, bytes] of rows) {
  console.log(`${String(bytes).padStart(10)}  ${(100 * bytes / totalMapped).toFixed(1).padStart(5)}%  ${lib}`)
}
