#!/usr/bin/env node
// CI guard of the theme boundaries (frontend/AGENTS.md), beside `eslint .`:
//   node scripts/check-theme-boundaries.mjs
// Fails when eslint-suppressions.json exists, when a file under src/ belongs to no theme or
// several, or when a comment switches the boundary rule off - by name, through inline config or by
// a blanket eslint-disable.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
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

function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const errors = checkNoSuppressions(root)
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
  console.log('Theme boundaries: every file assigned, no suppressions, no disabled boundary rule.')
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main()
}
