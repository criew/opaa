// The "offline" target of run-e2e.mjs: the scenario under e2e/offline/ against the stack of
// docker-compose.yml on a network without a route out (e2e/docker-compose.offline.yml).
//
// Phases: provide the images (build OPAA unless E2E_SKIP_BUILD=true, pull the rest), start the
// stack with --pull never, run the scenario, read the log of egress-dns and the container logs,
// tear down. Everything the installation needs is on the machine before the stack starts; from
// then on nothing is fetched, and the run fails on any name or address outside the stack.
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { envFileWithRunKeys } from './run-env-file.mjs'

const isWindows = process.platform === 'win32'

const SUBNET = '172.24.0.0/16'
const FRONTEND_ADDRESS = '172.24.0.10'
const DNS_ADDRESS = '172.24.0.53'
const SERVICES = ['postgres', 'ai-stub', 'egress-dns', 'backend', 'frontend', 'relay']
const INSTALLATION = ['postgres', 'ai-stub', 'backend', 'frontend']
// Messages of a connection attempt to an address without a route (no DNS involved).
const UNREACHABLE = /Network is unreachable|Network unreachable|No route to host/i

export async function runOffline({ repoRoot, e2eDir, extraTestArgs }) {
  const project = process.env.COMPOSE_PROJECT_NAME ?? 'opaa-offline'
  const frontendPort = process.env.OPAA_FRONTEND_PORT ?? '13003'
  const dnsLogPort = process.env.OPAA_OFFLINE_DNS_LOG_PORT ?? '18053'
  const baseUrl = `http://localhost:${frontendPort}`
  const composeArgs = [
    'compose',
    '-p',
    project,
    '-f',
    join(repoRoot, 'docker-compose.yml'),
    '-f',
    join(repoRoot, 'e2e/docker-compose.offline.yml'),
  ]
  const env = {
    ...process.env,
    COMPOSE_PROJECT_NAME: project,
    OPAA_ENV_FILE: envFileWithRunKeys(repoRoot, 'e2e/offline.env'),
    OPAA_COMPOSE_SUBNET: SUBNET,
    OPAA_COMPOSE_FRONTEND_ADDRESS: FRONTEND_ADDRESS,
    OPAA_OFFLINE_DNS_ADDRESS: DNS_ADDRESS,
    OPAA_FRONTEND_PORT: frontendPort,
    OPAA_OFFLINE_DNS_LOG_PORT: dnsLogPort,
    OPAA_INSTALLATION_DIR_HOST: './e2e/installation',
  }
  const admin = answerFileAdmin(e2eDir, 'offline')
  const run = (command, args, { cwd = repoRoot, extraEnv = {} } = {}) => {
    const result = spawnSync(command, args, {
      cwd,
      env: { ...env, ...extraEnv },
      stdio: 'inherit',
      shell: isWindows,
    })
    if (result.error) {
      throw result.error
    }
    return result.status ?? 1
  }
  const compose = (args) => run('docker', [...composeArgs, ...args])
  const teardown = () => compose(['down', '-v', '--remove-orphans'])

  teardown()
  rmSync(join(e2eDir, 'test-results', 'offline-result.json'), { force: true })
  console.log('\n> offline: providing images before the stack starts')
  let status = 0
  if (process.env.E2E_SKIP_BUILD !== 'true') {
    status = compose(['build', 'backend', 'frontend'])
  }
  if (status === 0) {
    status = compose(['pull', '--ignore-buildable', ...SERVICES])
  }
  if (status === 0) {
    console.log(`\n> offline: starting ${SERVICES.join(', ')} without pulling (${SUBNET}, internal)`)
    status = compose(['up', '-d', '--pull', 'never', '--no-build', ...SERVICES])
  }
  if (status === 0 && !(await waitUntilReady(`${baseUrl}/api/v1/auth/config`))) {
    console.error('The offline stack did not become ready within 8 minutes')
    status = 1
  }
  if (status === 0) {
    status = run(
      'pnpm',
      ['exec', 'playwright', 'test', '--config', 'offline/playwright.config.ts', ...extraTestArgs],
      {
        cwd: e2eDir,
        extraEnv: {
          E2E_BASE_URL: baseUrl,
          E2E_EGRESS_DNS_URL: `http://localhost:${dnsLogPort}`,
          E2E_BOOTSTRAP_ADMIN_EMAIL: admin.email,
          E2E_BOOTSTRAP_ADMIN_PASSWORD: admin.password,
        },
      },
    )
  }
  const logs = containerLogs(composeArgs, env)
  const unreachable = logs
    .split('\n')
    .filter((line) => INSTALLATION.some((service) => line.startsWith(service)) && UNREACHABLE.test(line))
  if (unreachable.length > 0) {
    console.error('\n> offline: connection attempts without a route in the container logs:')
    for (const line of unreachable) {
      console.error(`  ${line}`)
    }
    status = status || 1
  }
  recordLogFindings(e2eDir, unreachable)
  writeFileSync(join(e2eDir, 'docker-compose.offline.log'), logs)
  teardown()
  return status
}

/** Adds the log findings to the result of the scenario, or writes them alone if it has none. */
function recordLogFindings(e2eDir, unreachable) {
  const dir = join(e2eDir, 'test-results')
  const file = join(dir, 'offline-result.json')
  const result = existsSync(file)
    ? JSON.parse(readFileSync(file, 'utf8'))
    : { date: new Date().toISOString() }
  result.unreachableInLogs = unreachable
  mkdirSync(dir, { recursive: true })
  writeFileSync(file, `${JSON.stringify(result, null, 2)}
`)
}

/** The first account of e2e/installation/<name>.json, with the password from its password file. */
function answerFileAdmin(e2eDir, name) {
  const dir = join(e2eDir, 'installation')
  const { firstAdmin } = JSON.parse(readFileSync(join(dir, `${name}.json`), 'utf8'))
  const passwordFile = join(dir, firstAdmin.passwordFile.split('/').pop())
  return { email: firstAdmin.email, password: readFileSync(passwordFile, 'utf8').trim() }
}

async function waitUntilReady(url) {
  const deadline = Date.now() + 8 * 60_000
  while (Date.now() < deadline) {
    try {
      if ((await fetch(url)).ok) {
        return true
      }
    } catch {
      // not reachable yet
    }
    await new Promise((resolve) => setTimeout(resolve, 3000))
  }
  return false
}

function containerLogs(composeArgs, env) {
  const result = spawnSync('docker', [...composeArgs, 'logs', '--no-color', '--timestamps'], {
    env,
    encoding: 'utf8',
    maxBuffer: 512 * 1024 * 1024,
  })
  return `${result.stdout ?? ''}${result.stderr ?? ''}`
}
