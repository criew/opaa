// The env file a Compose stack of the E2E suite starts with: a copy of the target's env file in
// which every empty OPAA_CREDENTIALS_ENCRYPTION_KEY= or OPAA_SETTINGS_ENCRYPTION_KEY= line carries
// a key generated for this run. A key written into the repository would be public, and the "oidc"
// profile refuses those (SecretValidator). The copy lives until the process exits.
import { randomBytes } from 'node:crypto'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, join } from 'node:path'

const EMPTY_KEY = /^(OPAA_(?:CREDENTIALS|SETTINGS)_ENCRYPTION_KEY)=(\r?)$/gm

/** Absolute path of the filled copy of `envFile` (relative to `repoRoot`). */
export function envFileWithRunKeys(repoRoot, envFile) {
  const content = readFileSync(join(repoRoot, envFile), 'utf8').replace(
    EMPTY_KEY,
    (_, name, cr) => `${name}=${randomBytes(32).toString('base64')}${cr}`,
  )
  const dir = mkdtempSync(join(tmpdir(), 'opaa-e2e-env-'))
  process.on('exit', () => rmSync(dir, { recursive: true, force: true }))
  const path = join(dir, basename(envFile))
  writeFileSync(path, content, { mode: 0o600 })
  return path
}
