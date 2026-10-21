// Load generator of the rolling-update scenario (e2e/multi-instance/tests/05-rolling-update.spec.ts).
// Runs inside the platform's network (a container on the Compose network, a pod on kind) and talks
// to the frontend's nginx at BASE, as a browser does: reading calls of a signed-in administrator
// and, every MAIL_EVERY_MS, a sign-in of the bootstrap administrator, each of which puts one mail to
// every other system administrator into the outbox. The scenario later counts those mails. It stops
// once /tmp/stop exists and prints one line "LOAD_RESULT <json>".
import { existsSync } from 'node:fs'

const BASE = process.env.BASE
const TOKEN = process.env.TOKEN
const ADMIN_EMAIL = process.env.ADMIN_EMAIL
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD
const CONCURRENCY = Number(process.env.CONCURRENCY ?? 4)
const MAIL_EVERY_MS = Number(process.env.MAIL_EVERY_MS ?? 1000)
const REQUEST_TIMEOUT_MS = 30_000

const readPaths = ['/api/health', '/api/v1/auth/me', '/api/v1/libraries', '/api/v1/auth/config']

const result = {
  requests: 0,
  byOutcome: {},
  failures: [],
  notifyingSignIns: 0,
  signInFailures: [],
  longestGapMs: 0,
}
let lastSuccess = Date.now()
let stopping = false

function record(path, outcome) {
  result.requests += 1
  result.byOutcome[outcome] = (result.byOutcome[outcome] ?? 0) + 1
  const now = Date.now()
  if (/^2\d\d$/.test(outcome)) {
    result.longestGapMs = Math.max(result.longestGapMs, now - lastSuccess)
    lastSuccess = now
  } else if (result.failures.length < 200) {
    result.failures.push({ at: new Date(now).toISOString(), path, outcome })
  }
}

async function request(path, init = {}) {
  try {
    const response = await fetch(`${BASE}${path}`, {
      ...init,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
    await response.arrayBuffer()
    return String(response.status)
  } catch (error) {
    return `ERR:${error?.cause?.code ?? error?.name ?? 'unknown'}`
  }
}

async function reader(offset) {
  let index = offset
  while (!stopping) {
    const path = readPaths[index % readPaths.length]
    index += 1
    record(path, await request(path, { headers: { Authorization: `Bearer ${TOKEN}` } }))
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
}

async function signer() {
  while (!stopping) {
    const outcome = await request('/api/v1/auth/local/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    })
    record('/api/v1/auth/local/login', outcome)
    if (outcome === '200') {
      result.notifyingSignIns += 1
    } else {
      result.signInFailures.push({ at: new Date().toISOString(), outcome })
    }
    await new Promise((resolve) => setTimeout(resolve, MAIL_EVERY_MS))
  }
}

function watchStop() {
  return new Promise((resolve) => {
    const timer = setInterval(() => {
      if (existsSync('/tmp/stop')) {
        clearInterval(timer)
        resolve()
      }
    }, 200)
    process.on('SIGTERM', () => {
      clearInterval(timer)
      resolve()
    })
  })
}

const workers = [signer(), ...Array.from({ length: CONCURRENCY }, (_, i) => reader(i))]
await watchStop()
stopping = true
await Promise.all(workers)
result.longestGapMs = Math.max(result.longestGapMs, Date.now() - lastSuccess)
console.log(`LOAD_RESULT ${JSON.stringify(result)}`)
