#!/usr/bin/env node
// Usage: node scripts/move-files.mjs [--dry-run] <moves-file> | <old> <new> [<old> <new> ...]
// Moves files or folders (paths relative to frontend/) with `git mv` and rewrites every module
// path under src/ that points at them - moved files included. A path keeps its form (relative,
// @/, omitted extension or index).
// import.meta.glob() patterns and references outside src/ (index.html, vite.config.ts, scripts)
// are only reported. Workflow: frontend/AGENTS.md.
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SOURCE_DIR = 'src'
const SOURCE_EXTENSIONS = ['.ts', '.tsx', '.js', '.jsx', '.mjs']
const RESOLVE_SUFFIXES = ['', '.ts', '.tsx', '.d.ts', '.js', '.jsx', '.mjs', '.json']
const INDEX_SUFFIXES = ['/index.ts', '/index.tsx', '/index.js']

// Module paths in from '…', import '…' and import('…') (also inside a type argument), and in
// vi.mock('…'), vi.doMock('…') and vi.importActual<…>('…'). Group 1 is everything up to the
// quote, group 3 the path. Two patterns, because the second consumes the type argument that the
// first one rewrites.
const SPECIFIER_PATTERNS = [
  /(\bfrom\s*|\bimport\s*|\bimport\s*\(\s*)(['"])((?:\.{1,2}\/|@\/)[^'"]*)\2/g,
  /(\bvi\.(?:mock|doMock|importActual)\s*(?:<[^>()]*(?:\([^)]*\)[^>()]*)*>)?\s*\(\s*)(['"])((?:\.{1,2}\/|@\/)[^'"]*)\2/g,
]
const GLOB_PATTERN = /import\.meta\.glob(?:<[^>]*>)?\(\s*(['"])([^'"]+)\1/g

const toPosix = (p) => p.split(path.sep).join('/')

/** Parses "<old> <new>" lines; blank lines and # comments are skipped. */
export function parseMoves(text) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => {
      const parts = line.split(/\s+/)
      if (parts.length !== 2) throw new Error(`Expected "<old> <new>", got: ${line}`)
      return parts
    })
}

function listFiles(root, dir) {
  const absolute = path.join(root, dir)
  if (!fs.existsSync(absolute)) return []
  if (fs.statSync(absolute).isFile()) return [toPosix(dir)]
  return fs
    .readdirSync(absolute, { withFileTypes: true })
    .flatMap((entry) => listFiles(root, path.posix.join(toPosix(dir), entry.name)))
}

/** Expands folder moves to single files: Map of old path -> new path, relative to root. */
export function planMoves(root, moves) {
  const plan = new Map()
  for (const [rawFrom, rawTo] of moves) {
    const from = path.posix.normalize(toPosix(rawFrom)).replace(/\/$/, '')
    const to = path.posix.normalize(toPosix(rawTo)).replace(/\/$/, '')
    const absolute = path.join(root, from)
    if (!fs.existsSync(absolute)) throw new Error(`Source does not exist: ${from}`)
    if (fs.existsSync(path.join(root, to))) throw new Error(`Target already exists: ${to}`)
    if (fs.statSync(absolute).isFile()) {
      plan.set(from, to)
    } else {
      for (const file of listFiles(root, from)) plan.set(file, to + file.slice(from.length))
    }
  }
  const targets = new Set()
  for (const target of plan.values()) {
    if (targets.has(target)) throw new Error(`Two files would move to ${target}`)
    targets.add(target)
  }
  return plan
}

/** Resolves a module path to an existing file (relative to root), or null. */
function resolveSpecifier(base, fileExists) {
  for (const suffix of [...RESOLVE_SUFFIXES, ...INDEX_SUFFIXES]) {
    if (fileExists(base + suffix)) return { file: base + suffix, suffix }
  }
  return null
}

function specifierBase(specifier, fromFile) {
  if (specifier.startsWith('@/')) return path.posix.join(SOURCE_DIR, specifier.slice(2))
  return path.posix.normalize(path.posix.join(path.posix.dirname(fromFile), specifier))
}

function formatSpecifier(original, targetBase, fromFile) {
  if (original.startsWith('@/')) return '@/' + path.posix.relative(SOURCE_DIR, targetBase)
  const relative = path.posix.relative(path.posix.dirname(fromFile), targetBase)
  return relative.startsWith('.') ? relative : `./${relative}`
}

/**
 * Rewrites the module paths of one file's source. `oldFile`/`newFile` are its paths before and
 * after the move, `plan` maps old to new paths, `fileExists` answers for the state before it.
 */
export function rewriteSource(source, oldFile, newFile, plan, fileExists) {
  const rewrite = (match, lead, quote, specifier) => {
    const base = specifierBase(specifier, oldFile)
    const resolved = resolveSpecifier(base, fileExists)
    if (!resolved) return match
    const target = plan.get(resolved.file) ?? resolved.file
    if (target === resolved.file && newFile === oldFile) return match
    const targetBase = resolved.suffix
      ? target.endsWith(resolved.suffix)
        ? target.slice(0, -resolved.suffix.length)
        : target.replace(/\.[^./]+$/, '')
      : target
    const next = formatSpecifier(specifier, targetBase, newFile)
    return next === specifier ? match : `${lead}${quote}${next}${quote}`
  }
  return SPECIFIER_PATTERNS.reduce((text, pattern) => text.replace(pattern, rewrite), source)
}

/** import.meta.glob() patterns in a file whose set of matched files changes with the moves. */
export function findAffectedGlobs(source, oldFile, plan, allFiles) {
  const resolve = (pattern, from) =>
    pattern.startsWith('/')
      ? path.posix.normalize(pattern.slice(1))
      : path.posix.normalize(path.posix.join(path.posix.dirname(from), pattern))
  const hits = []
  for (const [, , pattern] of source.matchAll(GLOB_PATTERN)) {
    const before = resolve(pattern, oldFile)
    const after = resolve(pattern, plan.get(oldFile) ?? oldFile)
    const changed = allFiles.some(
      (candidate) =>
        path.posix.matchesGlob(candidate, before) !==
        path.posix.matchesGlob(plan.get(candidate) ?? candidate, after),
    )
    if (changed) hits.push(pattern)
  }
  return hits
}

function removeEmptyParents(root, dir) {
  for (let current = dir; current !== SOURCE_DIR && current !== '.';) {
    const absolute = path.join(root, current)
    if (!fs.existsSync(absolute) || fs.readdirSync(absolute).length > 0) return
    fs.rmdirSync(absolute)
    current = path.posix.dirname(current)
  }
}

const OUTSIDE_SKIP = new Set([
  SOURCE_DIR,
  'node_modules',
  'dist',
  'build',
  'public',
  'bundle-report',
])
const OUTSIDE_EXTENSIONS = ['.ts', '.js', '.mjs', '.cjs', '.html', '.json', '.yaml', '.yml', '.sh']
const OUTSIDE_IGNORED = new Set(['package.json', 'pnpm-lock.yaml'])

/**
 * Files outside src/ (configs, scripts, index.html) that name a moved path, with or without its
 * extension; they are reported, not rewritten.
 */
export function findOutsideReferences(root, plan, dir = '.') {
  const hits = []
  for (const entry of fs.readdirSync(path.join(root, dir), { withFileTypes: true })) {
    const file = path.posix.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (!OUTSIDE_SKIP.has(file) && !entry.name.startsWith('.')) {
        hits.push(...findOutsideReferences(root, plan, file))
      }
      continue
    }
    if (!OUTSIDE_EXTENSIONS.includes(path.posix.extname(file)) || OUTSIDE_IGNORED.has(file))
      continue
    const content = fs.readFileSync(path.join(root, file), 'utf8')
    for (const moved of plan.keys()) {
      const stem = moved.replace(/\.[^./]+$/, '')
      const escaped = stem.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const pattern = new RegExp(`${escaped}(?![\\w/-])`)
      if (pattern.test(content)) hits.push(`${file}: ${moved}`)
    }
  }
  return hits
}

/** Runs all moves below root; returns a report of the changed files. */
export function moveFiles(root, moves, { dryRun = false, log = console.log } = {}) {
  const plan = planMoves(root, moves)
  const before = new Set(listFiles(root, SOURCE_DIR))
  for (const file of plan.keys()) before.add(file)
  const fileExists = (file) => before.has(file)
  const sources = [...before].filter((file) => SOURCE_EXTENSIONS.includes(path.posix.extname(file)))

  const rewrites = new Map()
  const globWarnings = []
  for (const oldFile of sources) {
    const newFile = plan.get(oldFile) ?? oldFile
    const source = fs.readFileSync(path.join(root, oldFile), 'utf8')
    const rewritten = rewriteSource(source, oldFile, newFile, plan, fileExists)
    if (rewritten !== source) rewrites.set(newFile, rewritten)
    for (const pattern of findAffectedGlobs(source, oldFile, plan, [...before])) {
      globWarnings.push(`${newFile}: import.meta.glob('${pattern}')`)
    }
  }

  for (const [from, to] of plan) log(`${dryRun ? 'would move' : 'move'} ${from} -> ${to}`)
  for (const file of rewrites.keys()) log(`${dryRun ? 'would rewrite' : 'rewrite'} ${file}`)
  const outsideReferences = findOutsideReferences(root, plan)
  for (const warning of globWarnings) log(`check glob ${warning}`)
  for (const reference of outsideReferences) log(`check reference ${reference}`)
  if (dryRun) return { plan, rewrites, globWarnings, outsideReferences }

  for (const [from, to] of plan) {
    fs.mkdirSync(path.dirname(path.join(root, to)), { recursive: true })
    execFileSync('git', ['mv', from, to], { cwd: root })
  }
  for (const file of plan.keys()) removeEmptyParents(root, path.posix.dirname(file))
  for (const [file, content] of rewrites) fs.writeFileSync(path.join(root, file), content)
  return { plan, rewrites, globWarnings, outsideReferences }
}

function main(argv) {
  const dryRun = argv.includes('--dry-run')
  const args = argv.filter((arg) => arg !== '--dry-run')
  if (args.length === 0) {
    console.error('Usage: node scripts/move-files.mjs [--dry-run] <moves-file> | <old> <new>...')
    process.exit(2)
  }
  if (args.length > 1 && args.length % 2 !== 0) {
    console.error('Expected pairs of <old> <new>')
    process.exit(2)
  }
  const moves =
    args.length === 1
      ? parseMoves(fs.readFileSync(args[0], 'utf8'))
      : Array.from({ length: args.length / 2 }, (_, i) => [args[2 * i], args[2 * i + 1]])
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  moveFiles(root, moves, { dryRun })
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2))
}
