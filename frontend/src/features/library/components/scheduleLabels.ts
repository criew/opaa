import type { ScheduleFrequency, ScheduleWeekday } from '@/shared/api/types'

// Feste Intervallstufen für den Bibliotheks-Zeitplan - die Reihenfolge ist auch die
// Optionsreihenfolge in EditLibraryScheduleDialog.
export const scheduleFrequencies: ScheduleFrequency[] = ['DISABLED', 'HOURLY', 'DAILY', 'WEEKLY']

const scheduleFrequencyLabels: Record<ScheduleFrequency, string> = {
  DISABLED: 'Aus',
  HOURLY: 'Stündlich',
  DAILY: 'Täglich',
  WEEKLY: 'Wöchentlich',
}

export function scheduleFrequencyLabel(frequency: ScheduleFrequency | string | undefined): string {
  if (!frequency) return ''
  return scheduleFrequencyLabels[frequency as ScheduleFrequency] ?? frequency
}

export const scheduleWeekdays: ScheduleWeekday[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
]

const scheduleWeekdayLabels: Record<ScheduleWeekday, string> = {
  MONDAY: 'Montag',
  TUESDAY: 'Dienstag',
  WEDNESDAY: 'Mittwoch',
  THURSDAY: 'Donnerstag',
  FRIDAY: 'Freitag',
  SATURDAY: 'Samstag',
  SUNDAY: 'Sonntag',
}

export function scheduleWeekdayLabel(weekday: ScheduleWeekday | string | undefined): string {
  if (!weekday) return ''
  return scheduleWeekdayLabels[weekday as ScheduleWeekday] ?? weekday
}
