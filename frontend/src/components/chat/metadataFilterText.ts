import type {
  MetadataFilter,
  MetadataFilterLibraryFieldCondition,
  MetadataFilterOptionsResponse,
} from '../../types/api'
import { formatShare } from '../../utils/labels'

/** "Datum/Stand bei 92 % der Dokumente vorhanden" - the Füllstand line under each field. */
export function fillLevelText(field: {
  label: string
  filledDocuments: number
  totalDocuments: number
}): string {
  if (field.totalDocuments === 0)
    return `${field.label}: keine indizierten Dokumente im Suchbereich`
  return `${field.label} bei ${formatShare(field.filledDocuments / field.totalDocuments)} der Dokumente vorhanden`
}

/** Why a field is not offered - the entry condition, stated with its numbers. */
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

/** The library-field conditions of a filter, carried along by every chip removal. */
function libraryFieldsOf(filter: MetadataFilter) {
  return filter.libraryFields && filter.libraryFields.length > 0
    ? { libraryFields: filter.libraryFields }
    : {}
}

/** The filter without its Dokumentart condition - what removing that chip leaves. */
export function withoutDocumentTypes(filter: MetadataFilter): MetadataFilter {
  return {
    ...(filter.documentDateFrom ? { documentDateFrom: filter.documentDateFrom } : {}),
    ...(filter.documentDateTo ? { documentDateTo: filter.documentDateTo } : {}),
    ...libraryFieldsOf(filter),
  }
}

/** The filter without its date window - what removing that chip leaves. */
export function withoutDateWindow(filter: MetadataFilter): MetadataFilter {
  return {
    ...(filter.documentTypes && filter.documentTypes.length > 0
      ? { documentTypes: filter.documentTypes }
      : {}),
    ...libraryFieldsOf(filter),
  }
}

/**
 * "Fassung: Fassung 2026" - one chip per library-field condition. A condition names its
 * library, so two libraries with the same field key stay distinguishable in the chip bar.
 */
export function libraryFieldChipLabel(
  condition: MetadataFilterLibraryFieldCondition,
  options: MetadataFilterOptionsResponse | null,
): string {
  const field = (options?.libraryFields ?? []).find(
    (candidate) =>
      candidate.libraryId === condition.libraryId && candidate.fieldKey === condition.fieldKey,
  )
  const label = field?.label ?? condition.fieldKey
  if (condition.value) return `${label}: ${condition.value}`
  if (condition.dateFrom || condition.dateTo) {
    if (condition.dateFrom && condition.dateTo)
      return `${label}: ${germanDate(condition.dateFrom)} – ${germanDate(condition.dateTo)}`
    if (condition.dateFrom) return `${label}: ab ${germanDate(condition.dateFrom)}`
    return `${label}: bis ${germanDate(condition.dateTo as string)}`
  }
  const labels = (condition.codes ?? []).map(
    (code) => field?.values.find((value) => value.code === code)?.label ?? code,
  )
  return `${label}: ${labels.join(', ')}`
}

/** The filter without one library-field condition - what removing its chip leaves. */
export function withoutLibraryField(
  filter: MetadataFilter,
  condition: MetadataFilterLibraryFieldCondition,
): MetadataFilter {
  const remaining = (filter.libraryFields ?? []).filter(
    (candidate) =>
      candidate.libraryId !== condition.libraryId || candidate.fieldKey !== condition.fieldKey,
  )
  return {
    ...(filter.documentTypes && filter.documentTypes.length > 0
      ? { documentTypes: filter.documentTypes }
      : {}),
    ...(filter.documentDateFrom ? { documentDateFrom: filter.documentDateFrom } : {}),
    ...(filter.documentDateTo ? { documentDateTo: filter.documentDateTo } : {}),
    ...(remaining.length > 0 ? { libraryFields: remaining } : {}),
  }
}
