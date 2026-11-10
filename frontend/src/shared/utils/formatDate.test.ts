import { describe, expect, it } from 'vitest'
import {
  formatDate,
  formatDateTime,
  formatDayHeading,
  formatNumber,
  formatProtocolTime,
  formatTime,
  MISSING_VALUE,
} from './formatDate'

// Local dates, so the expectations hold in every time zone the tests run in.
const MOMENT = new Date(2026, 2, 5, 9, 4, 7)

describe('formatDate (guidelines 6)', () => {
  it('writes a date and a moment with leading zeros', () => {
    expect(formatDate(MOMENT)).toBe('05.03.2026')
    expect(formatDateTime(MOMENT)).toBe('05.03.2026, 09:04')
    expect(formatTime(MOMENT)).toBe('09:04')
  })

  it('shows seconds only in the protocol form', () => {
    expect(formatProtocolTime(MOMENT)).toBe('05.03.2026, 09:04:07')
    expect(formatDateTime(MOMENT)).not.toContain(':07')
  })

  it('accepts ISO strings and epoch milliseconds', () => {
    expect(formatDate(MOMENT.toISOString())).toBe('05.03.2026')
    expect(formatDateTime(MOMENT.getTime())).toBe('05.03.2026, 09:04')
  })

  it.each([null, undefined, '', 'kein Datum'])('shows „–“ for %j', (value) => {
    expect(MISSING_VALUE).toBe('–')
    expect(formatDate(value)).toBe(MISSING_VALUE)
    expect(formatDateTime(value)).toBe(MISSING_VALUE)
    expect(formatProtocolTime(value)).toBe(MISSING_VALUE)
    expect(formatTime(value)).toBe(MISSING_VALUE)
  })

  it('names weekday and month in the day separator of a conversation', () => {
    expect(formatDayHeading(MOMENT)).toBe('Donnerstag, 5. März 2026')
  })

  it('writes numbers in German notation', () => {
    expect(formatNumber(1234)).toBe('1.234')
    expect(formatNumber(0.25, { maximumFractionDigits: 1 })).toBe('0,3')
  })
})
