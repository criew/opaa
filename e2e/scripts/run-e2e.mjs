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
//
// Two targets, one script (Issue #232 - reuse this infrastructure instead
// of copying it):
//   node scripts/run-e2e.mjs                  # default target "e2e" (npm test)
//   node scripts/run-e2e.mjs --target demo     # demo-profile smoke test (npm run test:demo-smoke)
// The "demo" target starts the Compose "demo" profile (Rheinfurt corpus,
// Keycloak login, docs/features/demo-instance.md) with ai-stub standing in
// for the chat/embedding provider, seeds it with `demo/seed/seed.py
// --profile demo`, and runs the single Playwright spec under
// e2e/demo-smoke/tests/ - never the regular suite under e2e/tests/. It is
// deliberately not part of `npm test`: indexing the ~150-300 real
// documents of the Rheinfurt corpus takes far longer than the minimal e2e
// seed profile.

import { spawnSync } from 'node:child_process'
import { existsSync, writeFileSync } from 'node:fs'
import { join, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const e2eDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const repoRoot = resolve(e2eDir, '..')
const isWindows = process.platform === 'win32'

// "--target demo" / "--target=demo" picked out of argv before the rest is forwarded to
// Playwright as-is (extraTestArgs below) - a bare "npx playwright test --target demo" would
// otherwise fail with an unknown-option error.
const rawArgs = process.argv.slice(2)
let target = process.env.E2E_TARGET ?? 'e2e'
const targetFlagIndex = rawArgs.findIndex((arg) => arg === '--target' || arg.startsWith('--target='))
if (targetFlagIndex !== -1) {
  const flag = rawArgs[targetFlagIndex]
  if (flag.includes('=')) {
    target = flag.split('=')[1]
    rawArgs.splice(targetFlagIndex, 1)
  } else {
    target = rawArgs[targetFlagIndex + 1]
    rawArgs.splice(targetFlagIndex, 2)
  }
}
if (target !== 'e2e' && target !== 'demo') {
  console.error(`Unbekanntes --target "${target}" (erwartet: "e2e" oder "demo")`)
  process.exit(1)
}
const extraTestArgs = rawArgs
const isDemo = target === 'demo'

// The "demo" Compose profile's Keycloak realm (keycloak/realm-export.json) has a static
// redirectUris list scoped to http://localhost:3000, and this run's own
// OPAA_CSP_CONNECT_SRC_EXTRA/OPAA_OIDC_ISSUER_URI/OPAA_OIDC_AUTHORITY (e2e/demo-smoke.env) are all
// pinned to Keycloak's default port 8180 - unlike the "e2e" target, the *frontend* and *keycloak*
// ports are therefore not freely relocatable without also editing the realm export and that env
// file, so keycloakPort below is deliberately a fixed constant, not overridable via an env var the
// way every other port here is: a caller setting OPAA_KEYCLOAK_PORT to anything else would still
// only move where the *container* publishes its port, while the realm/env file's own references
// stayed at 8180 - breaking the login silently instead of loudly. Backend and database have no
// such static reference anywhere, so they reuse the "e2e" target's own non-default ports below -
// avoids colliding with a developer's own local Postgres/backend on the same host, exactly like
// the "e2e" target already does.
const composeProjectName =
  process.env.COMPOSE_PROJECT_NAME ?? (isDemo ? 'opaa-demo-smoke' : 'opaa-e2e')
const backendPort = process.env.OPAA_BACKEND_PORT ?? '18081'
const frontendPort = process.env.OPAA_FRONTEND_PORT ?? (isDemo ? '3000' : '13000')
const dbPort = process.env.OPAA_DB_PORT ?? '15432'
const keycloakPort = '8180'
const baseUrl = process.env.E2E_BASE_URL ?? `http://localhost:${frontendPort}`
const readyUrl = `${baseUrl}/api/v1/auth/config`
const skipBuild = process.env.E2E_SKIP_BUILD === 'true'

const overlayFile = isDemo ? 'e2e/docker-compose.demo-smoke.yml' : 'e2e/docker-compose.e2e.yml'
const envFile = isDemo ? 'e2e/demo-smoke.env' : 'e2e/e2e.env'
const composeArgs = [
  'compose',
  ...(isDemo ? ['--profile', 'demo'] : []),
  '-f',
  'docker-compose.yml',
  '-f',
  overlayFile,
]

// No auth secrets are needed for the "e2e" target: the stack runs in the "dev" auth profile,
// which has neither credentials nor a signing key (see e2e/e2e.env). The "demo" target's Keycloak
// realm carries only documented demo credentials (demo/README.md, "Demo-Zugangsdaten"), never a
// real secret either.

const composeEnv = {
  ...process.env,
  COMPOSE_PROJECT_NAME: composeProjectName,
  OPAA_ENV_FILE: envFile,
  OPAA_BACKEND_PORT: backendPort,
  OPAA_FRONTEND_PORT: frontendPort,
  OPAA_DB_PORT: dbPort,
  OPAA_KEYCLOAK_PORT: keycloakPort,
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
  const logPath = join(e2eDir, isDemo ? 'docker-compose.demo-smoke.log' : 'docker-compose.log')
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

// Fills the freshly started stack with test data (docs/features/demo-instance.md, "Installation
// und Seed" - Issue #233/#232): the same seed.py both the "demo" and "e2e" Compose profiles use,
// against this run's own backend/port - Keycloak login for "demo", the dev-auth header for "e2e".
const pythonCmd = isWindows ? 'python' : 'python3'
const venvDir = join(e2eDir, '.venv')
// A bare "pip install -r ..." would target whatever interpreter/site-packages "python"/"python3"
// resolves to system-wide (PR #726 review, finding 1) - on PEP-668-managed systems (Debian/Ubuntu
// >=23.04, Homebrew's own Python) that fails outright with "externally-managed-environment", and
// even where it does not fail it writes into a shared environment no other part of this repo
// touches. A dedicated venv under e2e/.venv (gitignored, reused across runs so a repeated `npm
// test` does not reinstall every time) sidesteps both: its own interpreter is never
// "externally managed", regardless of the host's system Python.
const venvPython = isWindows
  ? join(venvDir, 'Scripts', 'python.exe')
  : join(venvDir, 'bin', 'python3')

function runSeed() {
  if (!existsSync(venvPython)) {
    const createStatus = run(pythonCmd, ['-m', 'venv', venvDir])
    if (createStatus !== 0) {
      console.error(`Creating the venv at ${venvDir} failed`)
      return createStatus
    }
  }
  const installStatus = run(venvPython, [
    '-m',
    'pip',
    'install',
    '-q',
    '-r',
    'demo/seed/requirements.txt',
  ])
  if (installStatus !== 0) {
    console.error('pip install for demo/seed failed')
    return installStatus
  }
  const seedArgs = [
    'demo/seed/seed.py',
    '--profile',
    target,
    '--base-url',
    `http://localhost:${backendPort}/api`,
  ]
  if (isDemo) {
    // The Rheinfurt corpus (~150-300 documents across four connector-fed libraries plus 26
    // uploads) takes noticeably longer to index than the "e2e" profile's single seed document,
    // even with ai-stub's deterministic, near-instant embeddings - most of the time goes into
    // Tika parsing the PDF/DOCX/PPTX uploads and the HTTP_DIRECTORY/RSS_FEED crawls themselves.
    // Well above the seed's own 300s default (demo/seed/seed.py, parse_args).
    seedArgs.push('--keycloak-url', `http://localhost:${keycloakPort}`, '--indexing-timeout-seconds', '600')
  }
  return run(venvPython, seedArgs)
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

  // The "e2e" target names its services explicitly (only the ones this suite needs); the "demo"
  // target relies on `--profile demo` (already in composeArgs above) to pull in
  // keycloak/demo-corpus/demo-presse alongside the always-on postgres/backend/frontend/ai-stub -
  // naming a profile-gated service explicitly does not start it without its profile also active,
  // so composeArgs' `--profile demo` is what actually does the work either way.
  const services = isDemo
    ? ['postgres', 'backend', 'frontend', 'keycloak', 'demo-corpus', 'demo-presse', 'ai-stub']
    : ['ai-stub', 'rss-feed', 'postgres', 'backend', 'frontend']
  console.log(
    `> Starting ${isDemo ? 'demo' : 'E2E'} stack (${services.join(', ')}) as Compose project "${composeProjectName}"` +
      ` on ports ${dbPort}/${backendPort}/${frontendPort}`,
  )
  const upArgs = [...composeArgs, 'up', '-d', ...services]
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

  console.log(`> Seeding ${target} data profile (demo/seed/seed.py --profile ${target})`)
  const seedStatus = runSeed()
  if (seedStatus !== 0) {
    console.error('Seed run failed')
    dumpLogs()
    teardown()
    process.exitCode = seedStatus
    return
  }

  console.log('> Running Playwright tests')
  const playwrightArgs = ['playwright', 'test']
  if (isDemo) {
    // A dedicated config (testDir e2e/demo-smoke/tests) instead of testMatch on the default
    // e2e/playwright.config.ts - that keeps the demo smoke spec out of `npx playwright test`
    // (no path argument) as run by the "e2e" target and CI's e2e.yml, without either file needing
    // to know the other exists.
    playwrightArgs.push('--config', 'demo-smoke/playwright.config.ts')
  }
  playwrightArgs.push(...extraTestArgs)
  const testStatus = run(isWindows ? 'npx.cmd' : 'npx', playwrightArgs, {
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
