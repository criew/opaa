import { describe, expect, it } from 'vitest'
import { statusFilterOptions } from './documentStatusFilter'

describe('statusFilterOptions', () => {
  it('offers each state that shortens the list, with its number', () => {
    expect(statusFilterOptions({ FAILED: 3, PENDING: 1200, INDEXED: 40 }, null)).toEqual([
      ['FAILED', 'Fehlgeschlagen (3)'],
      ['PENDING', 'Wird verarbeitet (1.200)'],
    ])
  })

  it('offers no state that is empty or holds every row (guidelines 5.3)', () => {
    expect(statusFilterOptions({ FAILED: 0, PENDING: 2, INDEXED: 9 }, null)).toEqual([
      ['PENDING', 'Wird verarbeitet (2)'],
    ])
    expect(statusFilterOptions({ FAILED: 5, PENDING: 0, INDEXED: 0 }, null)).toEqual([])
    expect(statusFilterOptions({ FAILED: 0, PENDING: 0, INDEXED: 9 }, null)).toEqual([])
    expect(statusFilterOptions(undefined, null)).toEqual([])
    expect(statusFilterOptions(null, null)).toEqual([])
  })

  it('offers nothing below five rows in the whole library (guidelines 5.3)', () => {
    expect(statusFilterOptions({ FAILED: 1, PENDING: 0, INDEXED: 3 }, null)).toEqual([])
    expect(statusFilterOptions({ FAILED: 1, PENDING: 0, INDEXED: 4 }, null)).toEqual([
      ['FAILED', 'Fehlgeschlagen (1)'],
    ])
  })

  it('keeps the active state, so the select can show and clear it', () => {
    expect(statusFilterOptions({ FAILED: 0, PENDING: 0, INDEXED: 9 }, 'FAILED')).toEqual([
      ['FAILED', 'Fehlgeschlagen (0)'],
    ])
    expect(statusFilterOptions(undefined, 'PENDING')).toEqual([['PENDING', 'Wird verarbeitet']])
  })
})
