/**
 * The one place that turns a moment into German text (guidelines 6): a date is „05.03.2026“, a
 * moment „05.03.2026, 09:00“, both with leading zeros, in the browser's time zone. Seconds appear
 * only in protocols. ESLint rejects `toLocaleString`, `toLocaleDateString`, `toLocaleTimeString`
 * and `Intl.DateTimeFormat` everywhere else.
 */

type Moment = string | number | Date

/** What a list shows where a date or moment is missing. */
export const MISSING_VALUE = '–'

const DATE = new Intl.DateTimeFormat('de-DE', { day: '2-digit', month: '2-digit', year: 'numeric' })
const TIME = new Intl.DateTimeFormat('de-DE', { hour: '2-digit', minute: '2-digit' })
const TIME_SECONDS = new Intl.DateTimeFormat('de-DE', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
})
const DAY_HEADING = new Intl.DateTimeFormat('de-DE', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
})

function toDate(value: Moment): Date {
  return value instanceof Date ? value : new Date(value)
}

function format(value: Moment | null | undefined, render: (date: Date) => string): string {
  if (value === null || value === undefined || value === '') return MISSING_VALUE
  const date = toDate(value)
  return Number.isNaN(date.getTime()) ? MISSING_VALUE : render(date)
}

/** „05.03.2026“; „–“ for a missing or unreadable value. */
export function formatDate(value: Moment | null | undefined): string {
  return format(value, (date) => DATE.format(date))
}

/** „05.03.2026, 09:00“; „–“ for a missing or unreadable value. */
export function formatDateTime(value: Moment | null | undefined): string {
  return format(value, (date) => `${DATE.format(date)}, ${TIME.format(date)}`)
}

/** „05.03.2026, 09:00:41“ - only for protocols (revision, connection log). */
export function formatProtocolTime(value: Moment | null | undefined): string {
  return format(value, (date) => `${DATE.format(date)}, ${TIME_SECONDS.format(date)}`)
}

/** „09:00“, where the day is already clear from the context. */
export function formatTime(value: Moment | null | undefined): string {
  return format(value, (date) => TIME.format(date))
}

/** „Donnerstag, 5. März 2026“ - only as the separator between the days of a conversation. */
export function formatDayHeading(value: Moment): string {
  return format(value, (date) => DAY_HEADING.format(date))
}

/** A number in German notation („1.234“, „0,5“); `options` as for `Intl.NumberFormat`. */
export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  return value.toLocaleString('de-DE', options)
}
