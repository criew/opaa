#!/usr/bin/env node
// CI guard of the theme boundaries (frontend/AGENTS.md), beside `eslint .`:
//   node scripts/check-theme-boundaries.mjs
// Fails when eslint-suppressions.json exists, when a package.json script passes a suppression
// option to ESLint, when the effective ESLint config does not report the boundary rule as an error,
// when a file under src/ belongs to no theme or several, or when a comment switches the boundary
// rule off - by name, through inline config or by a blanket eslint-disable.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ESLint } from 'eslint'
import { findMisassigned, listSourceFiles } from '../eslint/assignment.js'

const SUPPRESSIONS_FILE = 'eslint-suppressions.json'
const SOURCE_EXTENSIONS = /\.(ts|tsx|js|jsx|mjs)$/

/** Returns the reason the frontend is not acceptable when it holds a bulk suppressions file. */
export function checkNoSuppressions(root) {
  return fs.existsSync(path.join(root, SUPPRESSIONS_FILE))
    ? [
        `${SUPPRESSIONS_FILE} must not exist - ESLint would apply it silently, and no rule ` +
          'violation is suppressed in bulk (frontend/AGENTS.md).',
      ]
    : []
}

const SUPPRESSION_OPTION = /--(?:suppressions-location|suppress-all|suppress-rule)(?![\w-])/

/** Scripts of a package.json that pass a bulk-suppression option to ESLint. */
export function checkScripts(packageJson) {
  return Object.entries(packageJson.scripts ?? {})
    .filter(([, command]) => SUPPRESSION_OPTION.test(command))
    .map(
      ([name, command]) =>
        `package.json script "${name}" (${command}) passes a suppression option - no rule ` +
        'violation is suppressed in bulk (frontend/AGENTS.md).',
    )
}

export const BOUNDARY_RULES = ['boundaries/dependencies']
/** One file per bucket kind; the boundary rules must report errors for each of them. */
export const SAMPLE_FILES = [
  'src/features/chat/pages/Sample.tsx',
  'src/features/chat/pages/Sample.test.tsx',
  'src/shared/ui/Sample.tsx',
  'src/app/Sample.tsx',
  'src/test/sample.ts',
]

const isError = (entry) => {
  const severity = Array.isArray(entry) ? entry[0] : entry
  return severity === 2 || severity === 'error'
}

/** Sample files whose effective config (via `eslint`) does not set every boundary rule to error. */
export async function checkBoundaryRules(eslint, root) {
  const errors = []
  for (const file of SAMPLE_FILES) {
    const config = await eslint.calculateConfigForFile(path.join(root, file))
    for (const rule of BOUNDARY_RULES) {
      if (!isError(config?.rules?.[rule])) {
        errors.push(`${file}: ESLint does not report ${rule} as an error - keep it on "error".`)
      }
    }
  }
  return errors
}

const DISABLE_DIRECTIVE = /(?:\/\/|\/\*)\s*eslint-disable(?:-next-line|-line)?(?![\w-])([^\n]*)/g
const INLINE_CONFIG = /\/\*\s*eslint\s[^]*?\*\//g

/** Comments that disable the boundary rule or every rule, as `line: text`. */
export function findBoundaryDisables(source) {
  const lineOf = (index) => source.slice(0, index).split('\n').length
  const hits = []
  for (const match of source.matchAll(DISABLE_DIRECTIVE)) {
    const rules = match[1]
      .replace(/\*\/.*$/, '')
      .split('--')[0]
      .split(',')
      .map((rule) => rule.trim())
      .filter(Boolean)
    if (rules.length === 0 || rules.some((rule) => rule.startsWith('boundaries/'))) {
      hits.push(`${lineOf(match.index)}: ${match[0].trim()}`)
    }
  }
  for (const match of source.matchAll(INLINE_CONFIG)) {
    if (match[0].includes('boundaries/')) hits.push(`${lineOf(match.index)}: ${match[0].trim()}`)
  }
  return hits
}

async function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const errors = checkNoSuppressions(root)
  errors.push(...checkScripts(JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))))
  errors.push(...(await checkBoundaryRules(new ESLint({ cwd: root }), root)))
  for (const { file, buckets } of findMisassigned(root)) {
    errors.push(
      `${file}: belongs to ${buckets.length === 0 ? 'no theme' : buckets.join(', ')} - assign it to exactly one in eslint/themes.js.`,
    )
  }
  for (const file of listSourceFiles(root).filter((f) => SOURCE_EXTENSIONS.test(f))) {
    for (const hit of findBoundaryDisables(fs.readFileSync(path.join(root, file), 'utf8'))) {
      errors.push(
        `${file}:${hit} - the boundary rule must not be disabled, not even by a blanket eslint-disable.`,
      )
    }
  }
  for (const error of errors) console.error(error)
  if (errors.length > 0) process.exit(1)
  console.log(
    'Theme boundaries: rule active, every file assigned, no suppressions, no disabled boundary rule.',
  )
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main()
}
