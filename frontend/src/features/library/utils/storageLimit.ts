export const BYTES_PER_GB = 1024 * 1024 * 1024

const MIN_LIMIT_BYTES = 1024 * 1024

/**
 * A typed limit in GB, with comma or point as decimal separator. A value below 1 MB or beyond the
 * safe integer range is refused; „unbegrenzt“ or „keine eigene Grenze“ never come from the field.
 */
export function parseLimit(draft: string): { bytes: number; error: string | null } {
  const text = draft.trim()
  if (!/^\d+([.,]\d+)?$/.test(text)) {
    return { bytes: 0, error: 'Bitte eine Zahl größer als 0 eingeben.' }
  }
  const gigabytes = Number(text.replace(',', '.'))
  if (gigabytes <= 0) return { bytes: 0, error: 'Bitte eine Zahl größer als 0 eingeben.' }
  const bytes = Math.round(gigabytes * BYTES_PER_GB)
  if (bytes < MIN_LIMIT_BYTES) {
    return { bytes: 0, error: 'Die Grenze muss mindestens 1 MB betragen.' }
  }
  if (!Number.isSafeInteger(bytes)) return { bytes: 0, error: 'Diese Grenze ist zu groß.' }
  return { bytes, error: null }
}

/** A limit in bytes as the field shows it, in GB with a decimal comma. */
export function limitDraft(bytes: number): string {
  return (bytes / BYTES_PER_GB).toLocaleString('de-DE', {
    maximumFractionDigits: 6,
    useGrouping: false,
  })
}
