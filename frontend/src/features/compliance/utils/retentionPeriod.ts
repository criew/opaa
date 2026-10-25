export type RetentionUnit = 'days' | 'years'
export type RetentionValues = Record<string, number | boolean>

export interface RetentionField {
  key: string
  label: string
  unit: RetentionUnit
  min: number
  max: number
}

export interface RetentionToggle {
  key: string
  label: string
}

/** A period in words: „1 Tag“, „90 Tage“, „1 Jahr“, „3 Jahre“. */
export function periodLabel(value: number, unit: RetentionUnit): string {
  if (unit === 'years') return value === 1 ? '1 Jahr' : `${value} Jahre`
  return value === 1 ? '1 Tag' : `${value} Tage`
}
