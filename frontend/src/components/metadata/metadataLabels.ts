import type { DatePrecision, MetadataOrigin } from '@/shared/api/types'

// #1068: provenance of a document metadata value (metadata-schema.md, "Jeder Wert trägt seine
// Herkunft") - a DERIVED value is always marked as such in the UI.
const metadataOriginLabels: Record<MetadataOrigin, string> = {
  DETERMINISTIC: 'automatisch ermittelt',
  DERIVED: 'abgeleitet',
  MANUAL: 'manuell',
}

export function metadataOriginLabel(origin: MetadataOrigin | string | null | undefined): string {
  if (!origin) return ''
  return metadataOriginLabels[origin as MetadataOrigin] ?? origin
}

export const datePrecisions: DatePrecision[] = ['DAY', 'MONTH', 'YEAR']

const datePrecisionLabels: Record<DatePrecision, string> = {
  DAY: 'Tag',
  MONTH: 'Monat',
  YEAR: 'Jahr',
}

export function datePrecisionLabel(precision: DatePrecision | string | null | undefined): string {
  if (!precision) return ''
  return datePrecisionLabels[precision as DatePrecision] ?? precision
}
