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
        return 'kein Zeitplan'
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
  const days = values.fullSyncDays.trim() === '' ? rhythm.defaultDays : Number(values.fullSyncDays)
  if (!days) return base
  return `${base} · Vollabgleich ${days === 1 ? 'täglich' : `alle ${days} Tage`}`
}
