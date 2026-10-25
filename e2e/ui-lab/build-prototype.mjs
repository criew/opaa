#!/usr/bin/env node
// Builds the clickable prototype (frontend `pnpm run build:prototype`), copies it to
// ui-lab/out/prototype/ and writes files.json: every file with its size, split into upload
// batches of at most 55 MB and 240 files for a static host such as a claude.ai artifact.
// Usage (from e2e/): node ui-lab/build-prototype.mjs [--skip-build] [--out <dir>]
// --out takes a directory, which is deleted and recreated; default ui-lab/out/prototype/.
import { execFileSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const MAX_BATCH_BYTES = 55 * 1024 * 1024
const MAX_BATCH_FILES = 240
// The service worker and Vite's manifest have no use in the in-page mock mode.
const EXCLUDED = new Set(['mockServiceWorker.js', '.vite'])

const here = dirname(fileURLToPath(import.meta.url))
const frontend = resolve(here, '../../frontend')
const args = process.argv.slice(2)
const outIndex = args.indexOf('--out')
if (outIndex >= 0 && !args[outIndex + 1]) {
  console.error('--out needs a directory')
  process.exit(1)
}
const out = outIndex >= 0 ? resolve(args[outIndex + 1]) : join(here, 'out', 'prototype')

if (!args.includes('--skip-build')) {
  execFileSync('pnpm', ['run', 'build:prototype'], {
    cwd: frontend,
    stdio: 'inherit',
  })
}
const dist = join(frontend, 'dist-prototype')
if (!existsSync(join(dist, 'index.html'))) {
  console.error(`no prototype build in ${dist}`)
  process.exit(1)
}

rmSync(out, { recursive: true, force: true })
cpSync(dist, out, {
  recursive: true,
  filter: (src) => !EXCLUDED.has(src.split(/[\\/]/).pop()),
})

function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    return entry.isDirectory() ? walk(path) : [path]
  })
}

// Static hosts such as claude.ai artifacts reject files carrying U+FFFD; the prototype build
// escapes every non-ASCII character in its scripts, so any left over is a build defect.
const TEXT = /\.(js|css|html|svg|json)$/
const defects = walk(out)
  .filter((path) => TEXT.test(path))
  .flatMap((path) => {
    const text = readFileSync(path, 'utf8')
    const name = relative(out, path)
    if (text.includes('\uFFFD')) return [`${name}: contains U+FFFD`]
    if (path.endsWith('.js') && /[^\x00-\x7f]/.test(text)) return [`${name}: non-ASCII character`]
    return []
  })
if (defects.length > 0) {
  console.error(defects.join('\n'))
  process.exit(1)
}

const files = walk(out)
  .filter((path) => relative(out, path) !== 'files.json')
  .map((path) => ({
    path: relative(out, path).split('\\').join('/'),
    bytes: statSync(path).size,
  }))
  .sort((a, b) => a.path.localeCompare(b.path))

// index.html is the page itself; the batches hold the files published next to it.
const batches = []
let current = { bytes: 0, files: [] }
for (const file of files.filter((f) => f.path !== 'index.html')) {
  if (current.files.length >= MAX_BATCH_FILES || current.bytes + file.bytes > MAX_BATCH_BYTES) {
    batches.push(current)
    current = { bytes: 0, files: [] }
  }
  current.files.push(file.path)
  current.bytes += file.bytes
}
if (current.files.length > 0) batches.push(current)

const totalBytes = files.reduce((sum, f) => sum + f.bytes, 0)
writeFileSync(
  join(out, 'files.json'),
  JSON.stringify({ page: 'index.html', totalBytes, files, batches }, null, 2),
)

console.log(`Prototyp: ${out}`)
console.log(`${files.length} Dateien, ${(totalBytes / 1024 / 1024).toFixed(1)} MB`)
batches.forEach((batch, i) => {
  console.log(
    `Paket ${i + 1}: ${batch.files.length} Dateien, ${(batch.bytes / 1024 / 1024).toFixed(1)} MB`,
  )
})
for (const file of files) console.log(`${String(file.bytes).padStart(10)}  ${file.path}`)
