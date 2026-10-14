#!/usr/bin/env node
// Proof of a pure move PR (frontend/AGENTS.md): node scripts/move-diff-check.mjs [<base-ref>]
// Compares every file changed since the merge base with its counterpart (rename pair, or same
// normalized content) after normalizing module paths: an import or export-from statement keeps
// only the names it binds, path arguments of import(), vi.mock(), vi.doMock() and vi.importActual()
// become a placeholder. Prints what still differs and exits 1; no output means only files moved.
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { SPECIFIER_PATTERNS } from './move-files.mjs'

const SOURCE = /\.(ts|tsx|js|jsx|mjs)$/
const SUPPRESSIONS_FILE = 'eslint-suppressions.json'
const PATH_PLACEHOLDER = "'<module>'"

// import/export … from '…' (clause may span lines) and side-effect imports, at line start.
const CLAUSE = String.raw`\s*(?:\{[^}]*\}|\*(?:\s+as\s+[\w$]+)?|[\w$]+(?:\s*,\s*(?:\{[^}]*\}|\*\s+as\s+[\w$]+))?)\s*`
const STATEMENT = new RegExp(
  String.raw`^[ \t]*(import|export)(?:[ \t]+type\b)?(${CLAUSE})from[ \t]*(['"])([^'"]*)\3[ \t]*;?` +
    String.raw`|^[ \t]*import[ \t]*(['"])([^'"]*)\5[ \t]*;?`,
  'gm',
)

function boundNames(clause) {
  const names = []
  const text = clause.trim()
  const braces = text.match(/\{([^}]*)\}/)
  const outside = text
    .replace(/\{[^}]*\}/, '')
    .replace(/,/g, ' ')
    .trim()
  for (const part of braces ? braces[1].split(',') : []) {
    const words = part
      .trim()
      .replace(/^type\s+/, '')
      .split(/\s+as\s+/)
    if (words[0]) names.push(words[words.length - 1].trim())
  }
  const star = outside.match(/\*\s*(?:as\s+(\w+))?/)
  if (star) names.push(star[1] ? `* as ${star[1]}` : '*')
  const plain = outside.replace(/\*\s*(?:as\s+\w+)?/, '').trim()
  if (plain) names.push(plain)
  return names.sort().join(', ')
}

/** Source text with module paths normalized away; see the header. */
export function normalize(source) {
  let text = source.replace(STATEMENT, (match, kind, clause, _q, _p, _q2, sideEffect) =>
    sideEffect !== undefined
      ? `import <side effect: ${path.posix.basename(sideEffect).replace(/\.[^.]+$/, '')}>`
      : `${kind} [${boundNames(clause)}]`,
  )
  // dynamic import() and vi.* path arguments; the first pattern's from/import parts are gone
  for (const pattern of SPECIFIER_PATTERNS) {
    text = text.replace(pattern, (match, lead) => `${lead}${PATH_PLACEHOLDER}`)
  }
  return text
}

/** Minimal line diff (LCS); returns '-'/'+' lines, empty when equal. */
export function lineDiff(before, after) {
  const a = before.split('\n')
  const b = after.split('\n')
  const table = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      table[i][j] =
        a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1])
    }
  }
  const out = []
  let i = 0
  let j = 0
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      i++
      j++
    } else if (j < b.length && (i === a.length || table[i][j + 1] >= table[i + 1][j])) {
      out.push(`+${b[j++]}`)
    } else {
      out.push(`-${a[i++]}`)
    }
  }
  return out
}

const canonical = (value) =>
  value && typeof value === 'object'
    ? `{${Object.keys(value)
        .sort()
        .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`)
        .join(',')}}`
    : JSON.stringify(value)

const isEntryOnly = (text) =>
  normalize(text)
    .split('\n')
    .every((line) => /^\s*$/.test(line) || /^export \[/.test(line) || /^\s*(\/\/|\/?\*)/.test(line))

/**
 * Checks a change set. `changes` are git name-status entries ({ status, from, to }), `readOld` and
 * `readNew` return file contents at the base and at HEAD. Returns the findings, empty when pure.
 */
export function checkChanges(changes, readOld, readNew) {
  const findings = []
  const renames = new Map()
  const deleted = changes.filter((c) => c.status === 'D').map((c) => c.from)
  const added = changes.filter((c) => c.status === 'A').map((c) => c.to)
  // pair deletions and additions git did not see as renames by their normalized content
  for (const from of [...deleted]) {
    if (!SOURCE.test(from)) continue
    const old = normalize(readOld(from))
    const to = added.find(
      (candidate) => SOURCE.test(candidate) && normalize(readNew(candidate)) === old,
    )
    if (to) {
      renames.set(from, to)
      deleted.splice(deleted.indexOf(from), 1)
      added.splice(added.indexOf(to), 1)
    }
  }
  const pairs = [...renames]
  for (const change of changes) {
    if (change.status === 'R' || change.status === 'C') {
      renames.set(change.from, change.to)
      pairs.push([change.from, change.to])
    }
    if (change.status === 'M') pairs.push([change.from, change.from])
  }
  for (const [from, to] of pairs) {
    if (to === SUPPRESSIONS_FILE) {
      const old = JSON.parse(readOld(from))
      const renamed = Object.fromEntries(
        Object.entries(old).map(([k, v]) => [renames.get(k) ?? k, v]),
      )
      const now = JSON.parse(readNew(to))
      if (canonical(renamed) !== canonical(now)) {
        findings.push(`${to}: entries changed beyond the renamed files`)
      }
      continue
    }
    const diff = SOURCE.test(to)
      ? lineDiff(normalize(readOld(from)), normalize(readNew(to)))
      : lineDiff(readOld(from), readNew(to))
    if (diff.length > 0)
      findings.push(`${from === to ? to : `${from} -> ${to}`}:\n${diff.join('\n')}`)
  }
  for (const to of added) {
    if (to.endsWith('.md')) continue
    if (/(^|\/)index\.ts$/.test(to) && isEntryOnly(readNew(to))) continue
    findings.push(`${to}: added`)
  }
  for (const from of deleted) findings.push(`${from}: deleted`)
  return findings
}

function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const git = (...args) =>
    execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 })
  const base = git('merge-base', process.argv[2] ?? 'origin/main', 'HEAD').trim()
  const changes = git(
    'diff',
    '--name-status',
    '--find-renames=30%',
    '--relative',
    base,
    'HEAD',
    '--',
    '.',
  )
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const [status, from, to] = line.split('\t')
      return { status: status[0], from, to: to ?? from }
    })
  const findings = checkChanges(
    changes,
    (file) => git('show', `${base}:./${file}`),
    (file) => git('show', `HEAD:./${file}`),
  )
  for (const finding of findings) console.log(finding)
  if (findings.length > 0) process.exit(1)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main()
}
