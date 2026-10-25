import type { SettingsDocument } from '@/shared/api/types'

/** The format name every settings document carries. */
export const SETTINGS_FORMAT = 'opaa-settings'

/** The largest file the backend reads; a larger one is refused before it is sent. */
export const MAX_SETTINGS_FILE_BYTES = 256 * 1024

/** `opaa-einstellungen-2026-10-25.json`, by the date of the export in local time. */
export function settingsFileName(exportedAt: Date): string {
  const day = [
    exportedAt.getFullYear(),
    String(exportedAt.getMonth() + 1).padStart(2, '0'),
    String(exportedAt.getDate()).padStart(2, '0'),
  ].join('-')
  return `opaa-einstellungen-${day}.json`
}

/** Offers `document` as a JSON file to save, indented so two exports can be compared by eye. */
export function downloadSettings(document: SettingsDocument, fileName: string): void {
  const blob = new Blob([JSON.stringify(document, null, 2)], { type: 'application/json' })
  const objectUrl = URL.createObjectURL(blob)
  try {
    const link = window.document.createElement('a')
    link.href = objectUrl
    link.download = fileName
    link.rel = 'noopener'
    window.document.body.appendChild(link)
    link.click()
    window.document.body.removeChild(link)
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}

/**
 * The document in `text`, or a German reason why it is none. Only the shape is checked here; the
 * values are checked by the backend, the same way the administration checks them.
 */
export function parseSettingsFile(
  text: string,
): { document: SettingsDocument } | { error: string } {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return { error: 'Die Datei ist kein JSON. Wählen Sie eine Datei aus dem Export von OPAA.' }
  }
  if (
    typeof parsed !== 'object' ||
    parsed === null ||
    (parsed as { format?: unknown }).format !== SETTINGS_FORMAT
  ) {
    return { error: 'Die Datei ist kein Einstellungsexport von OPAA.' }
  }
  return { document: parsed as SettingsDocument }
}

/** A value of the preview as text: `null` is the factory default, a list or object its JSON. */
export function formatSettingValue(value: unknown): string {
  if (value === null || value === undefined) return 'Werkseinstellung'
  if (typeof value === 'boolean') return value ? 'ein' : 'aus'
  if (typeof value === 'string') return value === '' ? '(leer)' : value
  if (typeof value === 'number') return String(value)
  if (Array.isArray(value) && value.length === 0) return '(keine)'
  return JSON.stringify(value)
}
