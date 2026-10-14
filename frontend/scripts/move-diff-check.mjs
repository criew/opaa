#!/usr/bin/env node
// Proof of a pure move PR (frontend/AGENTS.md): node scripts/move-diff-check.mjs [<base-ref>]
// Pairs every changed file under frontend/src/ with its counterpart (rename, or same normalized
// content) and compares both after resolving relative and @/ module paths to the module they name,
// mapped back through the renames; a theme index.ts that re-exports a module counts as that
// module, as does a routes.ts re-exporting default exports of the theme's own pages. An existing
// index.ts or routes.ts may only gain re-exports. Line breaks count only where they may end a
// statement, and the transition list may shrink. Bindings and package paths compare literally.
// Any other change - outside frontend/src/, a new file, an index.ts exporting a page, a routes.ts
// exporting anything else - is a finding. No output means only files moved.
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const SOURCE = /\.(ts|tsx|js|jsx|mjs)$/
const SUPPRESSIONS_FILE = 'eslint-suppressions.json'
const SUFFIXES = ['', '.ts', '.tsx', '.d.ts', '.js', '.jsx', '.mjs', '.json']
const INDEX_SUFFIXES = ['/index.ts', '/index.tsx', '/index.js']
const THEME_INDEX = /^src\/features\/[^/]+\/index\.ts$/
const THEME_ROUTES = /^src\/features\/([^/]+)\/routes\.ts$/

// import/export … from '…' (clause may span lines) and side-effect imports, at line start.
const CLAUSE = String.raw`\s*(?:\{[^}]*\}|\*(?:\s+as\s+[\w$]+)?|[\w$]+(?:\s*,\s*(?:\{[^}]*\}|\*\s+as\s+[\w$]+))?)\s*`
const STATEMENT = new RegExp(
  String.raw`^[ \t]*(import|export)([ \t]+type\b)?(${CLAUSE})from[ \t]*(['"])([^'"]*)\4[ \t]*;?` +
    String.raw`|^[ \t]*import[ \t]*(['"])([^'"]*)\6[ \t]*;?`,
  'gm',
)
// Module paths in import('…') (also in a type argument) and vi.mock/doMock/importActual('…').
const CALL_PATTERNS = [
  /(\bimport\s*\(\s*)(['"])([^'"]*)\2/g,
  /(\bvi\.(?:mock|doMock|importActual)\s*(?:<[^>()]*(?:\([^)]*\)[^>()]*)*>)?\s*\(\s*)(['"])([^'"]*)\2/g,
]

const isLocal = (spec) => spec.startsWith('.') || spec.startsWith('@/')

/** Bindings of an import/export clause as `imported as local`, in source order. */
function bindings(kind, clause) {
  const text = clause.trim()
  const result = []
  const plain = text.match(/^([\w$]+)\s*(,|$)/)
  if (plain && kind === 'import') result.push(`default as ${plain[1]}`)
  const star = text.match(/\*(?:\s+as\s+([\w$]+))?/)
  if (star) result.push(star[1] ? `* as ${star[1]}` : '*')
  const braces = text.match(/\{([^}]*)\}/)
  for (const part of braces ? braces[1].split(',') : []) {
    const spec = part.trim()
    if (!spec) continue
    const typeOnly = /^type\s+/.test(spec)
    const [imported, local = imported] = spec.replace(/^type\s+/, '').split(/\s+as\s+/)
    result.push(`${typeOnly ? 'type ' : ''}${imported.trim()} as ${local.trim()}`)
  }
  return result
}

/** All import/export-from statements of a source with their parts. */
export function parseStatements(source) {
  return [...source.matchAll(STATEMENT)].map((m) =>
    m[7] !== undefined
      ? { text: m[0], kind: 'import', typeOnly: false, bindings: [], spec: m[7], sideEffect: true }
      : {
          text: m[0],
          kind: m[1],
          typeOnly: Boolean(m[2]),
          bindings: bindings(m[1], m[3]),
          spec: m[5],
        },
  )
}

function makeResolver(files) {
  return (spec, from) => {
    const base = spec.startsWith('@/')
      ? path.posix.join('src', spec.slice(2))
      : path.posix.normalize(path.posix.join(path.posix.dirname(from), spec))
    for (const suffix of [...SUFFIXES, ...INDEX_SUFFIXES]) {
      if (files.has(base + suffix)) return { file: base + suffix }
    }
    return { unresolved: base }
  }
}

/**
 * Builds the normalizer of one side. `toBase` maps a path of this side to the base revision;
 * `entries` describes new theme index files (name -> { module, imported }) on the HEAD side.
 */
function makeNormalizer({ files, toBase, entries = new Map() }) {
  const resolve = makeResolver(files)
  const moduleToken = (spec, from) => {
    if (!isLocal(spec)) return `pkg:${spec}`
    const target = resolve(spec, from)
    return target.file ? `mod:${toBase(target.file)}` : `path:${toBase(target.unresolved)}`
  }
  return (source, file) => {
    let text = source.replace(STATEMENT, (match) => {
      const [statement] = parseStatements(match)
      if (!statement) return match
      if (statement.sideEffect) return `import <${moduleToken(statement.spec, file)}>`
      const target = isLocal(statement.spec) ? resolve(statement.spec, file) : {}
      const entry = target.file && entries.get(target.file)
      if (entry) {
        // through a theme's index.ts: each binding names the module and export behind it
        const parts = statement.bindings.map((binding) => {
          const [imported, local] = binding.replace(/^type /, '').split(' as ')
          const origin = entry.get(imported) ?? entry.get('*')
          const typePrefix = binding.startsWith('type ') ? 'type ' : ''
          const name = origin && origin.imported !== '*' ? origin.imported : imported
          return origin
            ? `${statement.kind}${statement.typeOnly ? ' type' : ''} [${typePrefix}${name} as ${local}] from <mod:${origin.module}>`
            : `${statement.kind} [${binding}] from <unknown export of ${target.file}>`
        })
        return parts.join('\n')
      }
      return statement.bindings
        .map(
          (binding) =>
            `${statement.kind}${statement.typeOnly ? ' type' : ''} [${binding}] from <${moduleToken(statement.spec, file)}>`,
        )
        .join('\n')
    })
    for (const pattern of CALL_PATTERNS) {
      text = text.replace(pattern, (match, lead, quote, spec) =>
        isLocal(spec) ? `${lead}<${moduleToken(spec, file)}>` : match,
      )
    }
    return text
  }
}

// Comments, string and template literals, module tokens of the normalizer, words, single marks.
const TOKEN =
  /\/\/[^\n]*|\/\*[\s\S]*?\*\/|'(?:\\.|[^'\\\n])*'|"(?:\\.|[^"\\\n])*"|`(?:\\[\s\S]|[^`\\])*`|<(?:mod|path|pkg):[^>\n]*>|=>|[\w$]+|\n|\S/g
// A line break after or before these marks never ends a statement.
const CONTINUES_AFTER = new Set(['(', '[', '{', ',', '=', '=>', '<', ':', '?', '.', '|', '&'])
const CONTINUES_BEFORE = new Set([')', ']', '}', ',', '.', '>', '?', ':', '=', '|', '&'])
const CLOSERS = new Set([')', ']', '}', '>'])

/**
 * Tokens of a normalized source, independent of the layout Prettier chooses: whitespace only
 * separates tokens, a line break counts only where it may end a statement, and a trailing comma
 * before a closing bracket is dropped.
 */
export function codeTokens(text) {
  const out = []
  for (const token of text.match(TOKEN) ?? []) {
    if (token === '\n') {
      if (out.length > 0 && out.at(-1) !== '\n') out.push(token)
      continue
    }
    if (out.at(-1) === '\n' && (CONTINUES_BEFORE.has(token) || CONTINUES_AFTER.has(out.at(-2)))) {
      out.pop()
    }
    if (CLOSERS.has(token) && out.at(-1) === ',') out.pop()
    out.push(token)
  }
  if (out.at(-1) === '\n') out.pop()
  return out
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

/**
 * Reads a theme index.ts or routes.ts: which module (`toBase` applied) and export stands behind
 * each exported name. `names` is null when the file holds more than re-exports of local modules.
 */
function readEntries(file, source, resolve, toBase) {
  const routesTheme = file.match(THEME_ROUTES)?.[1]
  const kind = routesTheme ? 'a routes.ts' : 'an index.ts'
  const statements = parseStatements(source)
  // whatever is left after removing the statements and real comments is code
  const rest = source
    .replace(STATEMENT, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .split('\n')
    .filter((line) => line.trim())
  if (rest.length > 0 || statements.some((s) => s.kind !== 'export' || !isLocal(s.spec))) {
    return { names: null, findings: [`${file}: ${kind} holds re-exports of local modules only`] }
  }
  const findings = []
  const names = new Map()
  for (const statement of statements) {
    const target = resolve(statement.spec, file)
    if (!target.file) {
      findings.push(`${file}: re-exports unresolvable ${statement.spec}`)
      continue
    }
    const isPage = /(^|\/)pages\//.test(target.file)
    if (!routesTheme && isPage) {
      findings.push(`${file}: exports the page ${target.file}`)
    }
    if (routesTheme && !target.file.startsWith(`src/features/${routesTheme}/pages/`)) {
      findings.push(`${file}: re-exports ${target.file}, no page of its theme`)
      continue
    }
    for (const binding of statement.bindings) {
      const [imported, exported = imported] = binding.replace(/^type /, '').split(' as ')
      if (routesTheme && imported !== 'default') {
        findings.push(`${file}: re-exports ${imported} of ${target.file}, not the page itself`)
        continue
      }
      names.set(exported, { module: toBase(target.file), imported })
    }
  }
  return { names, findings }
}

/** Entries of the transition list that grow against `before` (keys already renamed). */
function growingSuppressions(file, before, after) {
  const findings = []
  for (const [key, rules] of Object.entries(after)) {
    for (const [rule, entry] of Object.entries(rules)) {
      const was = before[key]?.[rule]?.count ?? 0
      if (!(entry.count <= was)) {
        findings.push(`${file}: ${key} ${rule} grows from ${was} to ${entry.count}`)
      }
    }
  }
  return findings
}

/**
 * Checks a change set. `changes` are name-status entries ({ status, from, to }) with paths
 * relative to frontend/ (`../` for files outside), `oldFiles`/`newFiles` the file lists at base and
 * HEAD, `readOld`/`readNew` the contents. Returns the findings, empty when the PR only moved files.
 */
export function checkChanges({ changes, oldFiles, newFiles, readOld, readNew }) {
  const findings = []
  const inScope = (file) => file.startsWith('src/') || file === SUPPRESSIONS_FILE
  for (const change of changes) {
    for (const file of new Set([change.from, change.to])) {
      if (!inScope(file)) findings.push(`${file}: changed outside frontend/src/`)
    }
  }
  const scoped = changes.filter((c) => inScope(c.from) && inScope(c.to))
  const renames = new Map()
  for (const c of scoped) if (c.status === 'R' || c.status === 'C') renames.set(c.from, c.to)
  const deleted = scoped.filter((c) => c.status === 'D').map((c) => c.from)
  const added = scoped.filter((c) => c.status === 'A').map((c) => c.to)

  const oldSet = new Set(oldFiles)
  const newSet = new Set(newFiles)
  const back = () => new Map([...renames].map(([from, to]) => [to, from]))
  const toBase = (file) => {
    const reverse = back()
    if (reverse.has(file)) return reverse.get(file)
    const stemHit = [...reverse].find(([to]) => to.replace(/\.[^./]+$/, '') === file)
    return stemHit ? stemHit[1].replace(/\.[^./]+$/, '') : file
  }
  const normalizeOld = makeNormalizer({ files: oldSet, toBase: (f) => f })

  // pair deletions and additions git did not see as renames by their normalized content
  for (const from of [...deleted]) {
    if (!SOURCE.test(from)) continue
    const old = normalizeOld(readOld(from), from)
    const to = added.find((candidate) => {
      if (!SOURCE.test(candidate)) return false
      const trial = makeNormalizer({ files: newSet, toBase })
      renames.set(from, candidate)
      const same = trial(readNew(candidate), candidate) === old
      renames.delete(from)
      return same
    })
    if (to) {
      renames.set(from, to)
      deleted.splice(deleted.indexOf(from), 1)
      added.splice(added.indexOf(to), 1)
    }
  }

  // theme index and routes files: which module and export stands behind each exported name
  const isEntryFile = (file) => THEME_INDEX.test(file) || THEME_ROUTES.test(file)
  const entries = new Map()
  const oldEntries = new Map()
  const resolveNew = makeResolver(newSet)
  const resolveOld = makeResolver(oldSet)
  for (const file of added.filter(isEntryFile)) {
    const { names, findings: own } = readEntries(file, readNew(file), resolveNew, toBase)
    findings.push(...own)
    if (!names) continue
    entries.set(file, names)
    added.splice(added.indexOf(file), 1)
  }
  // an existing one may only gain re-exports, each under the rules for a new file
  const grownEntries = new Set()
  for (const file of scoped.filter((c) => c.status === 'M' && isEntryFile(c.to)).map((c) => c.to)) {
    const now = readEntries(file, readNew(file), resolveNew, toBase)
    findings.push(...now.findings)
    if (!now.names) continue
    const before = readEntries(file, readOld(file), resolveOld, (f) => f)
    if (!before.names) {
      findings.push(`${file}: held more than re-exports before`)
      continue
    }
    for (const [name, origin] of before.names) {
      const current = now.names.get(name)
      if (!current) {
        findings.push(`${file}: removes the re-export ${name}`)
      } else if (current.module !== origin.module || current.imported !== origin.imported) {
        findings.push(`${file}: changes the re-export ${name}`)
      }
    }
    entries.set(file, now.names)
    oldEntries.set(file, before.names)
    grownEntries.add(file)
  }
  const normalizeNew = makeNormalizer({ files: newSet, toBase, entries })
  const normalizeOldEntries = makeNormalizer({
    files: oldSet,
    toBase: (f) => f,
    entries: oldEntries,
  })

  const pairs = [...renames]
  for (const c of scoped) {
    if (c.status === 'M' && !grownEntries.has(c.from)) pairs.push([c.from, c.from])
  }
  for (const [from, to] of pairs) {
    if (to === SUPPRESSIONS_FILE) {
      const old = JSON.parse(readOld(from))
      const renamed = Object.fromEntries(
        Object.entries(old).map(([k, v]) => [renames.get(k) ?? k, v]),
      )
      findings.push(...growingSuppressions(to, renamed, JSON.parse(readNew(to))))
      continue
    }
    let diff
    if (SOURCE.test(to)) {
      const before = normalizeOldEntries(readOld(from), from)
      const after = normalizeNew(readNew(to), to)
      const same = codeTokens(before).join('\0') === codeTokens(after).join('\0')
      diff = same ? [] : lineDiff(before, after)
    } else {
      diff = lineDiff(readOld(from), readNew(to))
    }
    if (diff.length > 0) {
      findings.push(`${from === to ? to : `${from} -> ${to}`}:\n${diff.join('\n')}`)
    }
  }
  for (const to of added) {
    if (/^src\/features\/[^/]+\/(AGENTS|CLAUDE)\.md$/.test(to)) continue
    findings.push(`${to}: added`)
  }
  for (const from of deleted) findings.push(`${from}: deleted`)
  return findings
}

function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const git = (...args) =>
    execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 1 << 28 })
  const prefix = git('rev-parse', '--show-prefix').trim()
  const local = (file) => (file.startsWith(prefix) ? file.slice(prefix.length) : `../${file}`)
  const full = (file) => (file.startsWith('../') ? file.slice(3) : prefix + file)
  const base = git('merge-base', process.argv[2] ?? 'origin/main', 'HEAD').trim()
  const changes = git('diff', '--name-status', '--find-renames=30%', base, 'HEAD')
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const [status, from, to] = line.split('\t')
      return { status: status[0], from: local(from), to: local(to ?? from) }
    })
  const list = (rev) =>
    git('ls-tree', '-r', '--name-only', '--full-tree', rev)
      .split('\n')
      .filter((file) => file.startsWith(prefix))
      .map(local)
  const findings = checkChanges({
    changes,
    oldFiles: list(base),
    newFiles: list('HEAD'),
    readOld: (file) => git('show', `${base}:${full(file)}`),
    readNew: (file) => git('show', `HEAD:${full(file)}`),
  })
  for (const finding of findings) console.log(finding)
  if (findings.length > 0) process.exit(1)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main()
}
