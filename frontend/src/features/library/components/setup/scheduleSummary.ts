import type { ConfluenceFullSyncRhythm, LibraryScheduleValues } from '../../utils/librarySchedule'
import { scheduleFrequencyLabel, scheduleWeekdayLabel } from '../scheduleLabels'
import type { SummaryRow } from '../sources/types'

/** The schedule as summary rows, worded like the form. */
export function scheduleSummary(
  values: LibraryScheduleValues,
  rhythm?: ConfluenceFullSyncRhythm,
): SummaryRow[] {
  const when =
    values.frequency === 'WEEKLY'
      ? `${scheduleFrequencyLabel(values.frequency)}, ${scheduleWeekdayLabel(values.weekday)} ${values.time} Uhr`
      : values.frequency === 'DAILY'
        ? `${scheduleFrequencyLabel(values.frequency)}, ${values.time} Uhr`
        : scheduleFrequencyLabel(values.frequency)
  const rows: SummaryRow[] = [{ label: 'Häufigkeit', value: when }]
  if (rhythm) {
    const days =
      values.fullSyncDays.trim() || (rhythm.defaultDays != null ? String(rhythm.defaultDays) : '')
    rows.push({ label: 'Vollabgleich', value: days ? `alle ${days} Tage` : 'Vorgabe der Instanz' })
  }
  return rows
}
