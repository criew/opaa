import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../../utils/librarySchedule'
import { scheduleWeekdayLabel } from '../scheduleLabels'

/** The schedule in one line: „täglich 02:00“, „montags 06:30“, „aus“. */
export function scheduleSummary(
  values: LibraryScheduleValues,
  rhythm?: ConfluenceFullSyncRhythm,
): string {
  const base = (() => {
    switch (values.frequency) {
      case 'DISABLED':
        return 'aus, nur „Jetzt indizieren“'
      case 'HOURLY':
        return 'stündlich'
      case 'DAILY':
        return `täglich ${values.time}`
      case 'WEEKLY':
        return `${scheduleWeekdayLabel(values.weekday).toLowerCase()}s ${values.time}`
      default:
        return String(values.frequency)
    }
  })()
  if (!rhythm) return base
  // without a value of its own the library follows the instance's default, 7 days unless set
  const days =
    values.fullSyncDays.trim() === '' ? (rhythm.defaultDays ?? 7) : Number(values.fullSyncDays)
  if (!days || values.frequency === 'DISABLED') return base
  return `${base} · Vollabgleich ${days === 1 ? 'täglich' : `alle ${days} Tage`}`
}
