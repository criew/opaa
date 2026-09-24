import type { LibrarySchedule, ScheduleFrequency, ScheduleWeekday } from '../types/api'

const DEFAULT_TIME = '03:00'

/** The library's own full-sync rhythm (#1200) - present only for a CONFLUENCE library. */
export interface ConfluenceFullSyncRhythm {
  /** `null` means "follows the instance-wide default". */
  intervalDays: number | null
  defaultDays: number | null
}

/** The schedule as typed, before it becomes a LibraryUpdateRequest. */
export interface LibraryScheduleValues {
  frequency: ScheduleFrequency
  /** "HH:mm"; only read for DAILY and WEEKLY. */
  time: string
  weekday: ScheduleWeekday
  /** Empty means "instance default"; only read for a CONFLUENCE library. */
  fullSyncDays: string
}

function partsToTimeString(
  hour: number | null | undefined,
  minute: number | null | undefined,
): string {
  if (hour == null || minute == null) return DEFAULT_TIME
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

function timeStringToParts(time: string): { hour: number; minute: number } | null {
  const match = /^(\d{2}):(\d{2})$/.exec(time)
  if (!match) return null
  return { hour: Number(match[1]), minute: Number(match[2]) }
}

/** The starting point of the form: a library's stored schedule, or the off state for a new one. */
export function scheduleValuesFrom(
  schedule: LibrarySchedule | null | undefined,
  confluence?: ConfluenceFullSyncRhythm,
): LibraryScheduleValues {
  return {
    frequency: schedule?.frequency ?? 'DISABLED',
    time: partsToTimeString(schedule?.hour, schedule?.minute),
    weekday: schedule?.weekday ?? 'MONDAY',
    fullSyncDays: confluence?.intervalDays != null ? String(confluence.intervalDays) : '',
  }
}

/** The German rejection of the typed values, or null when they are acceptable. */
export function validateScheduleValues(
  values: LibraryScheduleValues,
  confluence?: ConfluenceFullSyncRhythm,
): string | null {
  const needsTime = values.frequency === 'DAILY' || values.frequency === 'WEEKLY'
  if (needsTime && !timeStringToParts(values.time)) {
    return 'Bitte eine gültige Uhrzeit angeben.'
  }
  if (confluence) {
    const trimmed = values.fullSyncDays.trim()
    if (trimmed !== '') {
      const parsed = Number(trimmed)
      if (!Number.isInteger(parsed) || parsed < 1 || parsed > 365) {
        return 'Der Vollabgleich-Rhythmus muss zwischen 1 und 365 Tagen liegen.'
      }
    }
  }
  return null
}

/**
 * The schedule fields of a LibraryUpdateRequest/LibraryRequest. Call {@link validateScheduleValues}
 * first - this does not itself reject anything. #1200: an empty full-sync field returns the library
 * to the instance-wide default, sent as 0; the rhythm is lengthenable, never switchable off.
 */
export function scheduleUpdateFrom(
  values: LibraryScheduleValues,
  confluence?: ConfluenceFullSyncRhythm,
): {
  schedule: {
    frequency: ScheduleFrequency
    hour: number | null
    minute: number | null
    weekday?: ScheduleWeekday
  }
  confluenceFullSyncIntervalDays?: number
} {
  const needsTime = values.frequency === 'DAILY' || values.frequency === 'WEEKLY'
  const parts = needsTime ? timeStringToParts(values.time) : null
  const trimmed = values.fullSyncDays.trim()
  return {
    schedule: {
      frequency: values.frequency,
      hour: parts?.hour ?? null,
      minute: parts?.minute ?? null,
      weekday: values.frequency === 'WEEKLY' ? values.weekday : undefined,
    },
    ...(confluence ? { confluenceFullSyncIntervalDays: trimmed === '' ? 0 : Number(trimmed) } : {}),
  }
}
