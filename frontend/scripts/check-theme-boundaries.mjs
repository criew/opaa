#!/usr/bin/env node
// CI guard of the theme boundaries (frontend/AGENTS.md), beside `eslint .`:
//   node scripts/check-theme-boundaries.mjs [<base-ref>]   (default: origin/main)
// Fails when eslint-suppressions.json suppresses another rule or its total count exceeds the one
// at the base revision, when a file under src/ belongs to no theme or several, or when a comment
// switches the boundary rule off - by name, through inline config or by a blanket eslint-disable.
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { findMisassigned, listSourceFiles } from '../eslint/assignment.js'

export const BOUNDARY_RULE = 'boundaries/dependencies'
const SUPPRESSIONS_FILE = 'eslint-suppressions.json'
const SOURCE_EXTENSIONS = /\.(ts|tsx|js|jsx|mjs)$/

export function totalCount(suppressions) {
  return Object.values(suppressions)
    .flatMap((rules) => Object.values(rules))
    .reduce((sum, entry) => sum + entry.count, 0)
}

/** Returns the reasons the current list is not acceptable; empty when it is. */
export function checkSuppressions(current, base) {
  const errors = []
  for (const [file, rules] of Object.entries(current)) {
    for (const rule of Object.keys(rules)) {
      if (rule !== BOUNDARY_RULE) {
        errors.push(
          `${file}: ${rule} must not be suppressed - the transition list holds ${BOUNDARY_RULE} only.`,
        )
      }
    }
  }
  if (base !== null && totalCount(current) > totalCount(base)) {
    errors.push(
      `The transition list grew from ${totalCount(base)} to ${totalCount(current)} suppressed boundary ` +
        'violations. New dependencies across theme boundaries are not allowed (frontend/AGENTS.md).',
    )
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

function readBase(root, baseRef) {
  try {
    execFileSync('git', ['rev-parse', '--verify', '--quiet', `${baseRef}^{commit}`], { cwd: root })
  } catch {
    throw new Error(`Base revision ${baseRef} not found - fetch it first (git fetch origin main).`)
  }
  const spec = `${baseRef}:./${SUPPRESSIONS_FILE}`
  try {
    execFileSync('git', ['cat-file', '-e', spec], { cwd: root, stdio: 'ignore' })
  } catch {
    return null
  }
  const text = execFileSync('git', ['show', spec], { cwd: root, encoding: 'utf8' })
  return JSON.parse(text)
}

function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const baseRef = process.argv[2] ?? 'origin/main'
  const current = JSON.parse(fs.readFileSync(path.join(root, SUPPRESSIONS_FILE), 'utf8'))
  const base = readBase(root, baseRef)
  const errors = checkSuppressions(current, base)
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
  const baseInfo = base === null ? `no list at ${baseRef} yet` : `${baseRef}: ${totalCount(base)}`
  console.log(
    `Transition list: ${totalCount(current)} suppressed boundary violations (${baseInfo}).`,
  )
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main()
}
