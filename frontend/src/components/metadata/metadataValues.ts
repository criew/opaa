import type { DocumentTypeVocabularyEntryResponse, MetadataValueRequest } from '../../types/api'
import { datePrecisionLabel } from '../../utils/labels'

// The three core fields a person may set by hand (metadata-schema.md, Teil II (a)); the keys
// mirror CoreMetadataField in the backend.
export const CORE_METADATA_FIELDS: { key: string; label: string }[] = [
  { key: 'title', label: 'Titel' },
  { key: 'document_type', label: 'Dokumentart' },
  { key: 'document_date', label: 'Datum/Stand' },
]

export function coreMetadataFieldLabel(fieldKey: string): string {
  return CORE_METADATA_FIELDS.find((field) => field.key === fieldKey)?.label ?? fieldKey
}

/** Whether `value` carries everything the backend needs for `fieldKey`. */
export function isMetadataValueComplete(fieldKey: string, value: MetadataValueRequest): boolean {
  switch (fieldKey) {
    case 'title':
      return Boolean(value.textValue?.trim())
    case 'document_type':
      return Boolean(value.vocabularyCode)
    case 'document_date':
      return Boolean(value.dateValue) && Boolean(value.datePrecision)
    default:
      return false
  }
}

/** The request body for `fieldKey` alone - other fields' leftovers are dropped. */
export function metadataValueRequestFor(
  fieldKey: string,
  value: MetadataValueRequest,
): MetadataValueRequest {
  switch (fieldKey) {
    case 'title':
      return { textValue: value.textValue?.trim() ?? '' }
    case 'document_type':
      return { vocabularyCode: value.vocabularyCode ?? '' }
    default:
      return { dateValue: value.dateValue ?? '', datePrecision: value.datePrecision ?? 'DAY' }
  }
}

/** A human-readable rendering of `value` for the confirmation step. */
export function describeMetadataValue(
  fieldKey: string,
  value: MetadataValueRequest,
  vocabulary: DocumentTypeVocabularyEntryResponse[],
): string {
  switch (fieldKey) {
    case 'title':
      return value.textValue?.trim() ?? ''
    case 'document_type':
      return (
        vocabulary.find((entry) => entry.code === value.vocabularyCode)?.label ??
        value.vocabularyCode ??
        ''
      )
    default:
      return `${value.dateValue ?? ''} (Genauigkeit: ${datePrecisionLabel(value.datePrecision)})`
  }
}
