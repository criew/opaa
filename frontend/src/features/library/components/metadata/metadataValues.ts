import type {
  DocumentTypeVocabularyEntryResponse,
  LibraryMetadataFieldResponse,
  MetadataValueRequest,
} from '@/shared/api/types'
import { datePrecisionLabel } from './metadataLabels'

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

/** The third state carries no value and is valid for every core field. */
export function isNotDeterminable(value: MetadataValueRequest): boolean {
  return value.state === 'NOT_DETERMINABLE'
}

/** A library field's document key is namespaced (`lib:fassung`); core keys never are. */
export function isLibraryFieldKey(fieldKey: string): boolean {
  return fieldKey.startsWith('lib:')
}

export const NOT_DETERMINABLE_LABEL = 'kein Wert ermittelbar'

/**
 * Whether `value` carries everything the backend needs for `fieldKey`. A library field (key
 * `lib:…`) is judged by its type, so its definition must be passed; without it nothing is complete.
 */
export function isMetadataValueComplete(
  fieldKey: string,
  value: MetadataValueRequest,
  libraryField?: LibraryMetadataFieldResponse | null,
): boolean {
  if (isNotDeterminable(value)) return true
  if (isLibraryFieldKey(fieldKey)) {
    if (!libraryField) return false
    return libraryField.type === 'DATE'
      ? Boolean(value.dateValue) && Boolean(value.datePrecision)
      : Boolean(value.textValue?.trim())
  }
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
  libraryField?: LibraryMetadataFieldResponse | null,
): MetadataValueRequest {
  if (isNotDeterminable(value)) return { state: 'NOT_DETERMINABLE' }
  if (isLibraryFieldKey(fieldKey) && libraryField?.type !== 'DATE') {
    // SELECT carries the list code, PATTERN the identifier itself - both as textValue.
    return { textValue: value.textValue?.trim() ?? '' }
  }
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
  libraryField?: LibraryMetadataFieldResponse | null,
): string {
  if (isNotDeterminable(value)) return NOT_DETERMINABLE_LABEL
  if (isLibraryFieldKey(fieldKey) && libraryField?.type !== 'DATE') {
    const code = value.textValue?.trim() ?? ''
    return libraryField?.values.find((entry) => entry.code === code)?.label ?? code
  }
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
