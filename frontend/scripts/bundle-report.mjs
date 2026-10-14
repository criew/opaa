// Reports the JavaScript and CSS a first visit loads: the entry chunk with all its static
// imports (transitively) and, per route, the route's own chunk once the page is lazy-loaded.
// Reads dist/.vite/manifest.json (build.manifest), so run `pnpm run build` first.
// Usage: node scripts/bundle-report.mjs [outDir] - writes bundle-report.{md,json} into outDir.
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync, brotliCompressSync } from 'node:zlib'

const distDir = fileURLToPath(new URL('../dist', import.meta.url))
const outDir = process.argv[2] ?? fileURLToPath(new URL('../bundle-report', import.meta.url))

// Route -> source module of the page. While a page is not split into its own chunk, the route
// costs the entry alone; once it is, the page chunk and its not yet loaded imports are added.
const ROUTES = {
  '/login': 'src/features/auth/pages/LoginPage.tsx',
  '/spaces/:spaceId/chats/:chatId': 'src/pages/ChatPage.tsx',
  '/catalog': 'src/features/asset/pages/CatalogPage.tsx',
  '/admin/users': 'src/features/user/pages/UserManagementPage.tsx',
}

const manifest = JSON.parse(readFileSync(join(distDir, '.vite', 'manifest.json'), 'utf8'))
const entryKey = Object.keys(manifest).find((key) => manifest[key].isEntry)
if (!entryKey) throw new Error('No entry chunk in manifest')

/** Static imports reachable from key (including key), skipping the ones in `exclude`. */
function staticClosure(key, exclude = new Set()) {
  const seen = new Set()
  const visit = (k) => {
    if (seen.has(k) || exclude.has(k)) return
    seen.add(k)
    for (const dep of manifest[k].imports ?? []) visit(dep)
  }
  visit(key)
  return seen
}

function sizes(keys) {
  const files = new Set()
  for (const key of keys) {
    const chunk = manifest[key]
    files.add(chunk.file)
    for (const css of chunk.css ?? []) files.add(css)
  }
  const total = { files: files.size, raw: 0, gzip: 0, brotli: 0 }
  for (const file of files) {
    const content = readFileSync(join(distDir, file))
    total.raw += content.length
    total.gzip += gzipSync(content).length
    total.brotli += brotliCompressSync(content).length
  }
  return total
}

const entryKeys = staticClosure(entryKey)
const rows = [{ name: 'Einstieg', ...sizes(entryKeys) }]
for (const [route, module] of Object.entries(ROUTES)) {
  const chunk = manifest[module]
  if (!existsSync(fileURLToPath(new URL('../' + module, import.meta.url)))) {
    throw new Error(`Route ${route}: ${module} does not exist`)
  }
  const lazy = chunk?.isDynamicEntry ? staticClosure(module, entryKeys) : new Set()
  rows.push({ name: route, lazy: lazy.size > 0, ...sizes(new Set([...entryKeys, ...lazy])) })
}

const kib = (bytes) => (bytes / 1024).toFixed(1)
const markdown = [
  '| Route | Dateien | roh (KiB) | gzip (KiB) | brotli (KiB) | eigener Chunk |',
  '| --- | ---: | ---: | ---: | ---: | --- |',
  ...rows.map(
    (r) =>
      `| ${r.name} | ${r.files} | ${kib(r.raw)} | ${kib(r.gzip)} | ${kib(r.brotli)} | ${
        r.name === 'Einstieg' ? '-' : r.lazy ? 'ja' : 'nein'
      } |`,
  ),
  '',
].join('\n')

mkdirSync(outDir, { recursive: true })
writeFileSync(join(outDir, 'bundle-report.md'), markdown)
writeFileSync(join(outDir, 'bundle-report.json'), JSON.stringify(rows, null, 2) + '\n')
console.log(markdown)
