import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ESLint } from 'eslint'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { themeBoundaries } from '../eslint/boundaries.js'
import {
  SAMPLE_FILES,
  checkBoundaryRules,
  checkNoSuppressions,
  checkScripts,
  findBoundaryDisables,
} from './check-theme-boundaries.mjs'

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

describe('check-theme-boundaries', () => {
  let root

  beforeEach(() => {
    root = fs.mkdtempSync(path.join(os.tmpdir(), 'check-theme-boundaries-'))
  })

  afterEach(() => {
    fs.rmSync(root, { recursive: true, force: true })
  })

  it('accepts a frontend without a bulk suppressions file', () => {
    expect(checkNoSuppressions(root)).toEqual([])
  })

  it('rejects a bulk suppressions file, even an empty one', () => {
    fs.writeFileSync(path.join(root, 'eslint-suppressions.json'), '{}\n')

    const errors = checkNoSuppressions(root)

    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('eslint-suppressions.json must not exist')
  })

  it('finds comments that disable the boundary rule or every rule', () => {
    const source = [
      "import a from './a' // eslint-disable-line boundaries/dependencies",
      '// eslint-disable-next-line react-hooks/exhaustive-deps, boundaries/dependencies -- reason',
      '/* eslint-disable */',
      '// eslint-disable-next-line',
      '/* eslint boundaries/dependencies: off */',
      '// eslint-disable-next-line react-hooks/exhaustive-deps -- reason',
      '{/* eslint-disable-next-line jsx-a11y-x/no-autofocus */}',
      '/* eslint-enable */',
    ].join('\n')

    expect(findBoundaryDisables(source)).toEqual([
      '1: // eslint-disable-line boundaries/dependencies',
      '2: // eslint-disable-next-line react-hooks/exhaustive-deps, boundaries/dependencies -- reason',
      '3: /* eslint-disable */',
      '4: // eslint-disable-next-line',
      '5: /* eslint boundaries/dependencies: off */',
    ])
  })

  it.each([
    ['--suppressions-location', 'eslint . --suppressions-location other.json'],
    ['--suppress-all', 'eslint . --suppress-all'],
    ['--suppress-rule', 'eslint . --suppress-rule boundaries/dependencies'],
  ])('rejects a package.json script passing %s', (_, command) => {
    const errors = checkScripts({ scripts: { lint: 'eslint .', 'lint:quiet': command } })

    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('"lint:quiet"')
  })

  it('accepts scripts without suppression options', () => {
    expect(checkScripts({ scripts: { lint: 'eslint .', test: 'vitest run' } })).toEqual([])
  })

  it('accepts the effective config of this frontend', async () => {
    expect(await checkBoundaryRules(new ESLint({ cwd: frontendRoot }), frontendRoot)).toEqual([])
  })

  it.each([
    ['downgraded to warn', 'warn'],
    ['switched off', 'off'],
  ])('rejects the boundary rule %s', async (_, severity) => {
    const eslint = new ESLint({
      cwd: root,
      overrideConfigFile: true,
      overrideConfig: [
        themeBoundaries(root),
        { files: ['src/features/**'], rules: { 'boundaries/dependencies': severity } },
      ],
    })

    const errors = await checkBoundaryRules(eslint, root)

    const featureFiles = SAMPLE_FILES.filter((file) => file.startsWith('src/features/'))
    expect(errors).toHaveLength(featureFiles.length)
    expect(errors[0]).toContain('boundaries/dependencies')
  })

  it('rejects a config that does not cover a bucket at all', async () => {
    const eslint = new ESLint({
      cwd: root,
      overrideConfigFile: true,
      overrideConfig: [{ ...themeBoundaries(root), files: ['src/features/**/*.{ts,tsx}'] }],
    })

    const errors = await checkBoundaryRules(eslint, root)

    expect(errors.map((error) => error.split(':')[0])).toEqual([
      'src/shared/ui/Sample.tsx',
      'src/app/Sample.tsx',
      'src/test/sample.ts',
    ])
  })
})
