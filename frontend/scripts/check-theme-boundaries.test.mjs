import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { checkNoSuppressions, findBoundaryDisables } from './check-theme-boundaries.mjs'

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
})
