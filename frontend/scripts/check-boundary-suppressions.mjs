#!/usr/bin/env node
// Guards the transition list of the theme boundaries (eslint-suppressions.json).
//
//   node scripts/check-boundary-suppressions.mjs [<base-ref>]   (default: origin/main)
//
// Fails when the list suppresses any rule other than boundaries/dependencies, or when its total
// count exceeds the one at the base revision. That the list matches the code exactly is ESLint's
// part: `eslint .` fails on violations beyond it and on suppressions that no longer occur.
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const BOUNDARY_RULE = 'boundaries/dependencies'
const SUPPRESSIONS_FILE = 'eslint-suppressions.json'

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

function readBase(root, baseRef) {
  try {
    execFileSync('git', ['rev-parse', '--verify', '--quiet', `${baseRef}^{commit}`], { cwd: root })
  } catch {
    throw new Error(`Base revision ${baseRef} not found - fetch it first (git fetch origin main).`)
  }
  try {
    const text = execFileSync('git', ['show', `${baseRef}:./${SUPPRESSIONS_FILE}`], {
      cwd: root,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    })
    return JSON.parse(text)
  } catch {
    return null
  }
}

function main() {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const baseRef = process.argv[2] ?? 'origin/main'
  const current = JSON.parse(fs.readFileSync(path.join(root, SUPPRESSIONS_FILE), 'utf8'))
  const base = readBase(root, baseRef)
  const errors = checkSuppressions(current, base)
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
