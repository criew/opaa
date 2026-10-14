import { describe, expect, it } from 'vitest'
import { checkSuppressions, findBoundaryDisables, totalCount } from './check-theme-boundaries.mjs'

const entry = (count, rule = 'boundaries/dependencies') => ({ [rule]: { count } })

describe('check-theme-boundaries', () => {
  it('sums the counts of all entries', () => {
    expect(totalCount({ 'src/a.ts': entry(2), 'src/b.ts': entry(3) })).toBe(5)
  })

  it('accepts a list that shrank or kept its size, also with entries moved between files', () => {
    const base = { 'src/a.ts': entry(2), 'src/b.ts': entry(3) }

    expect(checkSuppressions({ 'src/a.ts': entry(2), 'src/b.ts': entry(2) }, base)).toEqual([])
    expect(
      checkSuppressions({ 'src/features/x/a.ts': entry(2), 'src/b.ts': entry(3) }, base),
    ).toEqual([])
  })

  it('rejects a list that grew', () => {
    const errors = checkSuppressions(
      { 'src/a.ts': entry(2), 'src/c.ts': entry(4) },
      { 'src/a.ts': entry(5) },
    )

    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('grew from 5 to 6')
  })

  it('rejects any other rule, even without a base list', () => {
    const errors = checkSuppressions({ 'src/a.ts': entry(1, 'no-console') }, null)

    expect(errors).toHaveLength(1)
    expect(errors[0]).toContain('no-console')
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
