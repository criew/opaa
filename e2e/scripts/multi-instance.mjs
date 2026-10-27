// The "multi-instance" target of run-e2e.mjs (ADR-0053): the scenarios under e2e/multi-instance/
// against the Compose variant with two backend instances (docker-compose.multi.yml). Two phases,
// each from an empty database:
//
//   fresh    the current version cold-started; scenarios 01-04, 06 and 07
//   rolling  the previous version (scripts/previous-version.mjs), updated to the current one by
//            scenario 05 in the order of the handbook
//
// Images: E2E_MULTI_BACKEND_IMAGE/E2E_MULTI_FRONTEND_IMAGE (current) and
// E2E_MULTI_PREVIOUS_BACKEND_IMAGE/E2E_MULTI_PREVIOUS_FRONTEND_IMAGE (previous), as CI builds them;
// without them this script builds both from the working tree and from the previous commit.
// E2E_MULTI_PHASES selects phases ("fresh,rolling" by default). The stack is always torn down.
import { spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { previousVersionRef } from './previous-version.mjs'

const isWindows = process.platform === 'win32'

const FRESH_SPECS = ['01-', '02-', '03-', '04-', '06-', '07-']
const ROLLING_SPECS = ['05-']

export async function runMultiInstance({ repoRoot, e2eDir, extraTestArgs }) {
  const project = process.env.COMPOSE_PROJECT_NAME ?? 'opaa-multi'
  const ports = {
    frontend: process.env.OPAA_FRONTEND_PORT ?? '13002',
    db: process.env.OPAA_DB_PORT ?? '15434',
    a: process.env.OPAA_BACKEND_A_PORT ?? '18183',
    b: process.env.OPAA_BACKEND_B_PORT ?? '18184',
    mailpitWeb: process.env.OPAA_MAILPIT_WEB_PORT ?? '18026',
    mailpitSmtp: process.env.OPAA_MAILPIT_SMTP_PORT ?? '11026',
    aiStub: process.env.OPAA_AI_STUB_PORT ?? '18090',
  }
  const corpusSize = process.env.OPAA_MULTI_CORPUS_SIZE ?? '40'
  const composeArgs = [
    'compose',
    '-p',
    project,
    '--profile',
    'upload-s3',
    '--profile',
    'mail',
    // Absolute, so that the scenarios can call Compose from e2e/ as well.
    '-f',
    join(repoRoot, 'docker-compose.yml'),
    '-f',
    join(repoRoot, 'docker-compose.multi.yml'),
    '-f',
    join(repoRoot, 'e2e/docker-compose.multi-instance.yml'),
  ]
  const baseEnv = {
    ...process.env,
    COMPOSE_PROJECT_NAME: project,
    OPAA_ENV_FILE: 'e2e/multi-instance.env',
    OPAA_COMPOSE_SUBNET: '172.26.0.0/16',
    OPAA_COMPOSE_FRONTEND_ADDRESS: '172.26.0.10',
    OPAA_FRONTEND_PORT: ports.frontend,
    // Absolute: the scenarios run Compose from e2e/ as well.
    OPAA_INSTALLATION_DIR_HOST: join(repoRoot, 'e2e/installation'),
    OPAA_DB_PORT: ports.db,
    OPAA_BACKEND_A_PORT: ports.a,
    OPAA_BACKEND_B_PORT: ports.b,
    OPAA_MAILPIT_WEB_PORT: ports.mailpitWeb,
    OPAA_MAILPIT_SMTP_PORT: ports.mailpitSmtp,
    OPAA_AI_STUB_PORT: ports.aiStub,
    OPAA_MULTI_CORPUS_SIZE: corpusSize,
  }

  const run = (command, args, { env = baseEnv, cwd = repoRoot } = {}) => {
    const result = spawnSync(command, args, { cwd, env, stdio: 'inherit', shell: isWindows })
    if (result.error) {
      throw result.error
    }
    return result.status ?? 1
  }
  const output = (command, args) =>
    spawnSync(command, args, { cwd: repoRoot, env: baseEnv, encoding: 'utf8' }).stdout.trim()

  const images = resolveImages({ repoRoot, run, output })
  const phases = (process.env.E2E_MULTI_PHASES ?? 'fresh,rolling').split(',').map((p) => p.trim())

  let status = 0
  for (const phase of phases) {
    const rolling = phase === 'rolling'
    const start = rolling ? images.previous : images.next
    const env = {
      ...baseEnv,
      OPAA_MULTI_BACKEND_IMAGE: start.backend,
      OPAA_MULTI_FRONTEND_IMAGE: start.frontend,
    }
    const compose = (args) => run('docker', [...composeArgs, ...args], { env })
    const teardown = () => compose(['down', '-v', '--remove-orphans'])

    console.log(`\n> multi-instance, phase "${phase}": ${start.backend} / ${start.frontend}`)
    teardown()
    let phaseStatus = compose(['up', '-d'])
    if (phaseStatus === 0) {
      phaseStatus = (await waitUntilReady(ports)) ? 0 : 1
      if (phaseStatus !== 0) {
        console.error('The two-instance stack did not become ready within 8 minutes')
      }
    }
    if (phaseStatus === 0) {
      phaseStatus = run(
        'pnpm',
        [
          'exec',
          'playwright',
          'test',
          '--config',
          'multi-instance/playwright.config.ts',
          ...(rolling ? ROLLING_SPECS : FRESH_SPECS),
          ...extraTestArgs,
        ],
        {
          cwd: e2eDir,
          env: {
            ...env,
            E2E_MULTI_PLATFORM: 'compose',
            E2E_COMPOSE_ARGS: JSON.stringify(composeArgs),
            E2E_COMPOSE_PROJECT: project,
            E2E_BASE_URL: `http://localhost:${ports.frontend}`,
            E2E_BACKEND_A_URL: `http://localhost:${ports.a}`,
            E2E_BACKEND_B_URL: `http://localhost:${ports.b}`,
            E2E_MAILPIT_BASE_URL: `http://localhost:${ports.mailpitWeb}`,
            E2E_AI_STUB_BASE_URL: `http://localhost:${ports.aiStub}`,
            E2E_CORPUS_SIZE: corpusSize,
            E2E_DB_POOL_SIZE: '5',
            E2E_NEXT_BACKEND_IMAGE: images.next.backend,
            E2E_NEXT_FRONTEND_IMAGE: images.next.frontend,
            E2E_EXPECT_VERSION_CHANGE: images.versionChanges ? 'true' : 'false',
          },
        },
      )
    }
    if (phaseStatus !== 0) {
      dumpLogs({ repoRoot, e2eDir, composeArgs, env, phase })
      status = phaseStatus
    }
    teardown()
    if (status !== 0) {
      break
    }
  }
  images.cleanup()
  return status
}

/** The images of both versions: given by the caller, or built here. */
function resolveImages({ repoRoot, run, output }) {
  const given = {
    next: {
      backend: process.env.E2E_MULTI_BACKEND_IMAGE,
      frontend: process.env.E2E_MULTI_FRONTEND_IMAGE,
    },
    previous: {
      backend: process.env.E2E_MULTI_PREVIOUS_BACKEND_IMAGE,
      frontend: process.env.E2E_MULTI_PREVIOUS_FRONTEND_IMAGE,
    },
  }
  const built = []
  const build = (context, dockerfile, tag, version) => {
    console.log(`> Building ${tag} from ${context}`)
    const status = run('docker', [
      'build',
      '-f',
      join(context, dockerfile),
      '-t',
      tag,
      ...(version ? ['--build-arg', `OPAA_VERSION=${version}`] : []),
      context,
    ])
    if (status !== 0) {
      throw new Error(`docker build ${tag} failed`)
    }
    built.push(tag)
    return tag
  }
  const head = output('git', ['rev-parse', '--short', 'HEAD'])
  if (!given.next.backend || !given.next.frontend) {
    given.next.backend ??= build(repoRoot, 'backend/Dockerfile', 'opaa-multi/backend:next', `0.0.0-dev+${head}`)
    given.next.frontend ??= build(repoRoot, 'frontend/Dockerfile', 'opaa-multi/frontend:next')
  }
  let workDir = null
  if (!given.previous.backend || !given.previous.frontend) {
    const ref = previousVersionRef()
    console.log(`> Previous version: ${ref}`)
    workDir = mkdtempSync(join(tmpdir(), 'opaa-previous-'))
    const archive = spawnSync('sh', ['-c', `git archive ${ref} | tar -x -C "${workDir}"`], {
      cwd: repoRoot,
      stdio: 'inherit',
    })
    if (archive.status !== 0) {
      throw new Error(`git archive ${ref} failed`)
    }
    const short = ref.slice(0, 7)
    given.previous.backend ??= build(workDir, 'backend/Dockerfile', 'opaa-multi/backend:previous', `0.0.0-dev+${short}`)
    given.previous.frontend ??= build(workDir, 'frontend/Dockerfile', 'opaa-multi/frontend:previous')
  }
  return {
    ...given,
    versionChanges: process.env.E2E_EXPECT_VERSION_CHANGE
      ? process.env.E2E_EXPECT_VERSION_CHANGE === 'true'
      : built.length === 4,
    cleanup() {
      if (workDir) {
        rmSync(workDir, { recursive: true, force: true })
      }
      if (process.env.E2E_KEEP_IMAGES !== 'true' && built.length > 0) {
        spawnSync('docker', ['rmi', ...built], { stdio: 'inherit' })
      }
    },
  }
}

async function waitUntilReady(ports) {
  const urls = [
    `http://localhost:${ports.frontend}/api/v1/auth/config`,
    `http://localhost:${ports.a}/api/health`,
    `http://localhost:${ports.b}/api/health`,
  ]
  const deadline = Date.now() + 8 * 60_000
  while (Date.now() < deadline) {
    const answers = await Promise.all(
      urls.map((url) =>
        fetch(url)
          .then((response) => response.ok)
          .catch(() => false),
      ),
    )
    if (answers.every(Boolean)) {
      return true
    }
    await new Promise((resolve) => setTimeout(resolve, 3000))
  }
  return false
}

function dumpLogs({ repoRoot, e2eDir, composeArgs, env, phase }) {
  const logPath = join(e2eDir, `docker-compose.multi-instance-${phase}.log`)
  console.log(`\n> Saving container logs to ${logPath}`)
  const result = spawnSync('docker', [...composeArgs, 'logs', '--no-color', '--timestamps'], {
    cwd: repoRoot,
    env,
    maxBuffer: 512 * 1024 * 1024,
  })
  writeFileSync(
    logPath,
    Buffer.concat([result.stdout ?? Buffer.alloc(0), result.stderr ?? Buffer.alloc(0)]),
  )
}
