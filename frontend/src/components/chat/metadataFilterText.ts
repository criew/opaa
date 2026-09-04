import type { MetadataFilter } from '../../types/api'
import { formatShare } from '../../utils/labels'

/** "Datum/Stand bei 92 % der Dokumente vorhanden" - the Füllstand line under each field (#1070). */
export function fillLevelText(field: {
  label: string
  filledDocuments: number
  totalDocuments: number
}): string {
  if (field.totalDocuments === 0)
    return `${field.label}: keine indizierten Dokumente im Suchbereich`
  return `${field.label} bei ${formatShare(field.filledDocuments / field.totalDocuments)} der Dokumente vorhanden`
}

/** Why a field is not offered - the entry condition, stated with its numbers (#1070). */
export function notOfferedText(field: {
  label: string
  filledDocuments: number
  totalDocuments: number
  threshold: number
}): string {
  if (field.totalDocuments === 0) {
    return `${field.label} wird nicht angeboten: Im Suchbereich sind keine Dokumente indiziert.`
  }
  return `${field.label} wird nicht angeboten: nur bei ${formatShare(field.filledDocuments / field.totalDocuments)} der Dokumente vorhanden (Schwelle ${formatShare(field.threshold)}).`
}

/** "01.01.2024" from an ISO calendar date, for the filter chip. */
function germanDate(iso: string): string {
  const [year, month, day] = iso.split('-')
  return `${day}.${month}.${year}`
}

/** "Datum: 01.01.2024 – 31.12.2024", "Datum: ab 01.01.2024" or "Datum: bis 31.12.2024". */
export function dateChipLabel(filter: MetadataFilter): string | undefined {
  const from = filter.documentDateFrom
  const to = filter.documentDateTo
  if (from && to) return `Datum: ${germanDate(from)} – ${germanDate(to)}`
  if (from) return `Datum: ab ${germanDate(from)}`
  if (to) return `Datum: bis ${germanDate(to)}`
  return undefined
}

/** The filter without its Dokumentart condition - what removing that chip leaves. */
export function withoutDocumentTypes(filter: MetadataFilter): MetadataFilter {
  return {
    ...(filter.documentDateFrom ? { documentDateFrom: filter.documentDateFrom } : {}),
    ...(filter.documentDateTo ? { documentDateTo: filter.documentDateTo } : {}),
  }
}

/** The filter without its date window - what removing that chip leaves. */
export function withoutDateWindow(filter: MetadataFilter): MetadataFilter {
  return filter.documentTypes && filter.documentTypes.length > 0
    ? { documentTypes: filter.documentTypes }
    : {}
}
