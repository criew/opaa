export const BYTES_PER_GB = 1024 * 1024 * 1024

const MIN_LIMIT_BYTES = 1024 * 1024

/** How far a typed limit may lie from a byte value it shows: half the last decimal of the field. */
export const DRAFT_PRECISION_BYTES = Math.ceil(BYTES_PER_GB * 0.0000005)

/**
 * A typed limit in GB, read as one of the `exact` byte values when it is exactly how the field
 * shows that value, so a limit that is no multiple of a GB survives being shown and saved; comma
 * or point separate decimals. A value below 1 MB or beyond the safe integer range is refused.
 */
export function parseLimit(
  draft: string,
  exact: readonly number[] = [],
): { bytes: number; error: string | null } {
  const text = draft.trim()
  const shown = exact.find((bytes) => bytes > 0 && limitDraft(bytes) === text)
  if (shown !== undefined) return { bytes: shown, error: null }
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
