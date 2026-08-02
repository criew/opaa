#!/usr/bin/env node
// Orchestrates a full E2E run: bring the stack up from a clean slate via
// Docker Compose, wait until it answers, run Playwright, then always tear
// the stack back down (`down -v`) so the next run starts from the same
// defined baseline. Used both locally (`npm test` in e2e/) and in CI
// (.github/workflows/e2e.yml), so both environments behave identically.

import { spawnSync } from 'node:child_process'
import { existsSync, copyFileSync, readFileSync, rmSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const e2eDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = resolve(e2eDir, '..')
const isWindows = process.platform === 'win32'
const baseUrl = process.env.E2E_BASE_URL ?? 'http://localhost:3000'
const readyUrl = `${baseUrl}/api/v1/auth/config`
const composeArgs = ['compose', '-f', 'docker-compose.yml']
const extraTestArgs = process.argv.slice(2)

// docker-compose.yml pins fixed container_name values (opaa-postgres,
// opaa-backend, opaa-frontend, opaa-keycloak), so `docker compose down`
// scoped to a different Compose project (e.g. a stray container from
// another worktree, or a plain `docker compose up` run outside of one of
// the scripted flows) won't be cleaned up by our own `down -v` below, and
// `up` then fails with a container-name conflict. Force-removing them by
// name first guarantees the clean slate the E2E suite needs regardless of
// where a same-named leftover container came from.
const fixedContainerNames = ['opaa-postgres', 'opaa-backend', 'opaa-frontend', 'opaa-keycloak']

// docker-compose.yml requires a root-level .env.docker file (env_file:) for
// the postgres/backend services. To keep the E2E stack fully self-contained
// (throwaway test credentials, placeholder AI key, no dependency on a
// developer's own .env.docker), we temporarily install e2e/e2e.env as
// .env.docker for the duration of the run and restore whatever was there
// before, so this script never permanently overwrites a developer's own
// configuration.
const envDockerPath = join(repoRoot, '.env.docker')
const envDockerBackupPath = join(repoRoot, '.env.docker.e2e-backup')
const e2eEnvPath = join(e2eDir, 'e2e.env')

function run(command, args, cwd = repoRoot, env = process.env) {
  const result = spawnSync(command, args, {
    cwd,
    stdio: 'inherit',
    shell: isWindows,
    env,
  })
  if (result.error) {
    throw result.error
  }
  return result.status ?? 1
}

// e2e/e2e.env is the single source of truth for the test user credentials
// (OPAA_AUTH_BASIC_USERNAME/PASSWORD, consumed by the backend container).
// Read them back here so Playwright (running on the host, not in a
// container) logs in with the exact same credentials, instead of
// duplicating them in a second place that could drift out of sync.
function readE2eEnvVar(name) {
  const contents = readFileSync(e2eEnvPath, 'utf8')
  const match = contents.match(new RegExp(`^${name}=(.*)$`, 'm'))
  return match?.[1]
}

function installE2eEnvFile() {
  if (existsSync(envDockerPath) && !existsSync(envDockerBackupPath)) {
    copyFileSync(envDockerPath, envDockerBackupPath)
  }
  copyFileSync(e2eEnvPath, envDockerPath)
}

function restoreEnvFile() {
  if (existsSync(envDockerBackupPath)) {
    copyFileSync(envDockerBackupPath, envDockerPath)
    rmSync(envDockerBackupPath)
  } else {
    rmSync(envDockerPath, { force: true })
  }
}

function removeStaleFixedNameContainers() {
  run('docker', ['rm', '-f', ...fixedContainerNames])
}

function teardown() {
  console.log('\n> Tearing down E2E stack (docker compose down -v)')
  run('docker', [...composeArgs, 'down', '-v', '--remove-orphans'])
  removeStaleFixedNameContainers()
  restoreEnvFile()
}

async function waitUntilReady(timeoutMs) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const response = await fetch(readyUrl)
      if (response.ok) {
        return true
      }
    } catch {
      // Stack is not reachable yet; keep polling until the timeout.
    }
    await new Promise((r) => setTimeout(r, 2000))
  }
  return false
}

async function main() {
  installE2eEnvFile()

  console.log('> Starting from a clean slate (docker compose down -v)')
  run('docker', [...composeArgs, 'down', '-v', '--remove-orphans'])
  removeStaleFixedNameContainers()

  console.log('> Starting E2E stack (postgres, backend, frontend)')
  const upStatus = run('docker', [
    ...composeArgs,
    'up',
    '-d',
    '--build',
    'postgres',
    'backend',
    'frontend',
  ])
  if (upStatus !== 0) {
    console.error('docker compose up failed')
    teardown()
    process.exitCode = upStatus
    return
  }

  console.log(`> Waiting for ${readyUrl} to become reachable`)
  const ready = await waitUntilReady(120_000)
  if (!ready) {
    console.error('Stack did not become ready within 120s')
    run('docker', [...composeArgs, 'logs'])
    teardown()
    process.exitCode = 1
    return
  }

  console.log('> Running Playwright tests')
  const testStatus = run(
    isWindows ? 'npx.cmd' : 'npx',
    ['playwright', 'test', ...extraTestArgs],
    e2eDir,
    {
      ...process.env,
      E2E_USERNAME: readE2eEnvVar('OPAA_AUTH_BASIC_USERNAME'),
      E2E_PASSWORD: readE2eEnvVar('OPAA_AUTH_BASIC_PASSWORD'),
    },
  )

  teardown()
  process.exitCode = testStatus
}

main().catch((error) => {
  console.error(error)
  teardown()
  process.exitCode = 1
})
