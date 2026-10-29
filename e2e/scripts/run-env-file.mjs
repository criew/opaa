// The env file a Compose stack of the E2E suite starts with: a copy of the target's env file in
// which every empty OPAA_CREDENTIALS_ENCRYPTION_KEY=, OPAA_SETTINGS_ENCRYPTION_KEY= or
// OPAA_AUTH_JWT_SECRET= line carries a value generated for this run. A value written into the
// repository would be public, and the "oidc" profile refuses those (SecretValidator). The copy is
// removed when the process exits, also on SIGINT/SIGTERM.
import { randomBytes } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, join } from 'node:path'

// Variable and number of random bytes: AES-256 keys as from `openssl rand -base64 32`, the signing
// secret as from `openssl rand -base64 48`.
const GENERATED = {
  OPAA_CREDENTIALS_ENCRYPTION_KEY: 32,
  OPAA_SETTINGS_ENCRYPTION_KEY: 32,
  OPAA_AUTH_JWT_SECRET: 48,
}
const EMPTY_LINE = new RegExp(`^(${Object.keys(GENERATED).join('|')})=(\\r?)$`, 'gm')
// Directories of the copies this process made, removed on exit.
const copies = new Set()

/** Absolute path of the filled copy of `envFile` (relative to `repoRoot`). */
export function envFileWithRunKeys(repoRoot, envFile) {
  const content = readFileSync(join(repoRoot, envFile), 'utf8').replace(
    EMPTY_LINE,
    (_, name, cr) => `${name}=${randomBytes(GENERATED[name]).toString('base64')}${cr}`,
  )
  const dir = mkdtempSync(join(tmpdir(), 'opaa-e2e-env-'))
  if (copies.size === 0) {
    registerCleanup()
  }
  copies.add(dir)
  const path = join(dir, basename(envFile))
  writeFileSync(path, content, { mode: 0o600 })
  return path
}

function registerCleanup() {
  process.on('exit', () => {
    for (const dir of copies) {
      rmSync(dir, { recursive: true, force: true })
    }
  })
  // A caller with a signal handler of its own still needs the copy for its `compose down` and ends
  // the process itself; without one, the process ends here with the signal's usual exit code. Both
  // paths remove the copy through the exit handler.
  for (const [signal, code] of [
    ['SIGINT', 130],
    ['SIGTERM', 143],
  ]) {
    process.on(signal, () => {
      if (process.listenerCount(signal) === 1) {
        process.exit(code)
      }
    })
  }
}
