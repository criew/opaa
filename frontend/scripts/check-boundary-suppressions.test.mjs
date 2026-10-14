import { describe, expect, it } from 'vitest'
import { checkSuppressions, totalCount } from './check-boundary-suppressions.mjs'

const entry = (count, rule = 'boundaries/dependencies') => ({ [rule]: { count } })

describe('check-boundary-suppressions', () => {
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
})
