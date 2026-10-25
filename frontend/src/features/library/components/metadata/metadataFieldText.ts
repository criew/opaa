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
  if (field.citationPosition != null) chips.push(`Beleg ${field.citationPosition}`)
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

/**
 * An example value of a simple pattern („^[0-9]{2}\\.[0-9]{2}-[0-9]{4}$“ → „12.34-5678“), so the
 * row shows the format instead of the raw expression; null for anything beyond digits, letters,
 * escaped literals and fixed counts - the pattern itself stays in the dialog „Ändern“.
 */
export function patternExample(pattern: string): string | null {
  const DIGITS = '1234567890'
  let out = ''
  let digit = 0
  let i = pattern.startsWith('^') ? 1 : 0
  const end = pattern.endsWith('$') ? pattern.length - 1 : pattern.length
  while (i < end) {
    let unit: () => string
    if (pattern.startsWith('[0-9]', i) || pattern.startsWith('\\d', i)) {
      unit = () => DIGITS[digit++ % 10]
      i += pattern.startsWith('[0-9]', i) ? 5 : 2
    } else if (pattern.startsWith('[A-Z]', i)) {
      unit = () => 'A'
      i += 5
    } else if (pattern[i] === '\\' && i + 1 < end && /[.\-/ ]/.test(pattern[i + 1])) {
      const literal = pattern[i + 1]
      unit = () => literal
      i += 2
    } else if (/[A-Za-z0-9\-/ :_]/.test(pattern[i])) {
      const literal = pattern[i]
      unit = () => literal
      i += 1
    } else {
      return null
    }
    let count = 1
    const quantifier = /^\{(\d+)(?:,\d*)?\}/.exec(pattern.slice(i, end))
    if (quantifier) {
      count = Number(quantifier[1])
      i += quantifier[0].length
    } else if (/[+*?]/.test(pattern[i] ?? '')) {
      return null
    }
    for (let n = 0; n < count; n++) out += unit()
  }
  return out || null
}
