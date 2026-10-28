import { plural } from '@/shared/utils/format'
import type {
  LibraryMetadataFieldResponse,
  LibraryMetadataSchemaChangeResponse,
  LibraryMetadataFieldType,
} from '@/shared/api/types'

export const TYPE_LABELS: Record<LibraryMetadataFieldType, string> = {
  SELECT: 'Auswahl aus einer Werteliste',
  DATE: 'Jahr oder Datum',
  PATTERN: 'Kennung nach Muster',
}

/** The Wirkstellen of a field as short chips - what the Aufnahmeregel makes visible. */
export function effectChips(field: LibraryMetadataFieldResponse) {
  const chips: string[] = []
  if (field.filter) chips.push('Filter')
  if (field.contextPrefix) chips.push('Kontextpräfix')
  return chips
}

/**
 * A running schema change in one sentence: what is retired and how far the run is. The
 * field is named with its configured name, not with its key — the key is a
 * technical identifier that has no place in the interface.
 */
export function describeChange(
  change: LibraryMetadataSchemaChangeResponse,
  fields: LibraryMetadataFieldResponse[],
): string {
  const fieldLabel =
    fields.find((field) => field.fieldKey === change.fieldKey)?.label ?? change.fieldKey
  const progress = `${plural(change.remainingDocuments, 'Dokument offen', 'Dokumente offen')}, ${change.processedDocuments} umgeschrieben.`
  if (change.kind === 'FIELD_DELETION') {
    return `Feld „${fieldLabel}“ wird gelöscht: ${progress}`
  }
  const target = change.targetCode == null ? 'leer' : `„${change.targetCode}“`
  return `Wert „${change.valueCode}“ des Feldes „${fieldLabel}“ wird auf ${target} abgebildet: ${progress}`
}

/** "rund 40 Minuten" - a runtime a decision can be made on, never a raw number of seconds. */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return 'unter einer Minute'
  const minutes = Math.round(seconds / 60)
  if (minutes < 90) return `rund ${minutes} Minute${minutes === 1 ? '' : 'n'}`
  const hours = Math.round(minutes / 60)
  return `rund ${hours} Stunde${hours === 1 ? '' : 'n'}`
}
