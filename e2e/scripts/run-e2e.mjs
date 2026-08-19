#!/usr/bin/env node
// Orchestrates a full E2E run: bring the stack up from a clean slate via
// Docker Compose, wait until it answers, run Playwright, then always tear
// the stack back down (`down -v`) so the next run starts from the same
// defined baseline. Used both locally (`npm test` in e2e/) and in CI
// (.github/workflows/e2e.yml), so both environments behave identically.
//
// The stack runs as its own Compose project (COMPOSE_PROJECT_NAME below),
// which prefixes container/network/volume names so it never collides with
// a developer's own docker-compose.yml stack running at the same time (see
// AGENTS.md "Git Worktrees für parallele Sessions"). It also uses its own
// host ports and its own env file (OPAA_ENV_FILE, see docker-compose.yml),
// so this script never reads or writes a developer's own .env.docker.

import { spawnSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const e2eDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = resolve(e2eDir, '..')
const isWindows = process.platform === 'win32'

const composeProjectName = process.env.COMPOSE_PROJECT_NAME ?? 'opaa-e2e'
const backendPort = process.env.OPAA_BACKEND_PORT ?? '18081'
const frontendPort = process.env.OPAA_FRONTEND_PORT ?? '13000'
const dbPort = process.env.OPAA_DB_PORT ?? '15432'
const baseUrl = process.env.E2E_BASE_URL ?? `http://localhost:${frontendPort}`
const readyUrl = `${baseUrl}/api/v1/auth/config`
const skipBuild = process.env.E2E_SKIP_BUILD === 'true'
const extraTestArgs = process.argv.slice(2)

const composeArgs = ['compose', '-f', 'docker-compose.yml', '-f', 'e2e/docker-compose.e2e.yml']

// No auth secrets are needed: the stack runs in the "dev" auth profile,
// which has neither credentials nor a signing key (see e2e/e2e.env).

const composeEnv = {
  ...process.env,
  COMPOSE_PROJECT_NAME: composeProjectName,
  OPAA_ENV_FILE: 'e2e/e2e.env',
  OPAA_BACKEND_PORT: backendPort,
  OPAA_FRONTEND_PORT: frontendPort,
  OPAA_DB_PORT: dbPort,
}

let tornDown = false

function run(command, args, { cwd = repoRoot, env = process.env } = {}) {
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

function dumpLogs() {
  const logPath = join(e2eDir, 'docker-compose.log')
  console.log(`\n> Saving container logs to ${logPath}`)
  const result = spawnSync('docker', [...composeArgs, 'logs', '--no-color', '--timestamps'], {
    cwd: repoRoot,
    shell: isWindows,
    env: composeEnv,
  })
  try {
    writeFileSync(
      logPath,
      Buffer.concat([result.stdout ?? Buffer.alloc(0), result.stderr ?? Buffer.alloc(0)]),
    )
  } catch (error) {
    // Best-effort: log capture failures must never mask the real test result.
    console.error('Failed to write docker-compose.log', error)
  }
}

function teardown() {
  if (tornDown) {
    return
  }
  tornDown = true
  console.log('\n> Tearing down E2E stack (docker compose down -v)')
  run('docker', [...composeArgs, 'down', '-v', '--remove-orphans'], { env: composeEnv })
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`\n> Received ${signal}, tearing down before exit`)
    teardown()
    process.exit(130)
  })
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
  console.log(`> Starting from a clean slate (docker compose -p ${composeProjectName} down -v)`)
  run('docker', [...composeArgs, 'down', '-v', '--remove-orphans'], { env: composeEnv })

  console.log(
    `> Starting E2E stack (ai-stub, rss-feed, postgres, backend, frontend) as Compose project "${composeProjectName}"` +
      ` on ports ${dbPort}/${backendPort}/${frontendPort}`,
  )
  const upArgs = [...composeArgs, 'up', '-d', 'ai-stub', 'rss-feed', 'postgres', 'backend', 'frontend']
  if (!skipBuild) {
    upArgs.splice(upArgs.indexOf('up') + 1, 0, '--build')
  }
  const upStatus = run('docker', upArgs, { env: composeEnv })
  if (upStatus !== 0) {
    console.error('docker compose up failed')
    dumpLogs()
    teardown()
    process.exitCode = upStatus
    return
  }

  console.log(`> Waiting for ${readyUrl} to become reachable`)
  const ready = await waitUntilReady(120_000)
  if (!ready) {
    console.error('Stack did not become ready within 120s')
    dumpLogs()
    teardown()
    process.exitCode = 1
    return
  }

  console.log('> Running Playwright tests')
  const testStatus = run(isWindows ? 'npx.cmd' : 'npx', ['playwright', 'test', ...extraTestArgs], {
    cwd: e2eDir,
    env: {
      ...process.env,
      E2E_BASE_URL: baseUrl,
    },
  })

  if (testStatus !== 0) {
    dumpLogs()
  }

  teardown()
  process.exitCode = testStatus
}

main().catch((error) => {
  console.error(error)
  dumpLogs()
  teardown()
  process.exitCode = 1
})
