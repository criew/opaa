#!/usr/bin/env node
// The commit of "version n-1" for the rolling-update scenario of the two-instance target
// (docs/entwicklung/build-und-ci.md, "Zwei-Instanzen-Suite"):
//
//   1. E2E_MULTI_PREVIOUS_REF, if set;
//   2. the release v<X> when backend/src/main/resources/instance/rolling-update.properties names X
//      as the oldest compatible version - the version a release promises to roll from;
//   3. otherwise main as it stood LOOKBACK_HOURS before the base (the merge base with origin/main:
//      for a pull request the main it branched from, on main its own head), but never older than
//      FLOOR, the first state keeping the embedding model in the database: an installation
//      from before it is not taken over (ADR-0060, Entscheidung 10), nor one migrated by Liquibase
//      (ADR-0056, Entscheidung 2), so no rolling update crosses that line.
//
// Prints the full commit hash. Needs the history of main (fetch-depth 0 in CI).
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** The first state with the embedding model in the database; after the switch to Flyway (ADR-0056). */
export const FLOOR = '8ce3fc9224275cf3cea82c90eb132ee01b89491f'
export const LOOKBACK_HOURS = 24

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')

function git(args, { allowFailure = false } = {}) {
  const result = spawnSync('git', args, { cwd: repoRoot, encoding: 'utf8' })
  if (result.status !== 0 && !allowFailure) {
    throw new Error(`git ${args.join(' ')} failed: ${result.stderr}`)
  }
  return result.status === 0 ? result.stdout.trim() : ''
}

function declaredOldestCompatible() {
  const file = join(repoRoot, 'backend/src/main/resources/instance/rolling-update.properties')
  const line = readFileSync(file, 'utf8')
    .split('\n')
    .find((entry) => entry.trim().startsWith('oldest-compatible-version='))
  const value = line ? line.split('=')[1].trim() : ''
  return value === '' ? null : value
}

export function previousVersionRef() {
  const explicit = process.env.E2E_MULTI_PREVIOUS_REF
  if (explicit) {
    return git(['rev-parse', `${explicit}^{commit}`])
  }
  const declared = declaredOldestCompatible()
  if (declared) {
    return git(['rev-parse', `v${declared}^{commit}`])
  }
  const base = git(['merge-base', 'HEAD', 'origin/main'], { allowFailure: true }) || git(['rev-parse', 'HEAD'])
  const baseTime = Number(git(['show', '-s', '--format=%ct', base]))
  const before = baseTime - LOOKBACK_HOURS * 3600
  const candidate = git(['rev-list', '-1', '--first-parent', `--before=${before}`, base])
  if (!candidate) {
    return FLOOR
  }
  const floorIsAncestor =
    spawnSync('git', ['merge-base', '--is-ancestor', FLOOR, candidate], { cwd: repoRoot }).status === 0
  return floorIsAncestor ? candidate : FLOOR
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  console.log(previousVersionRef())
}
