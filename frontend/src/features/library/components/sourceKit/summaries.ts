/** One row of the step „Prüfen“. */
export interface SummaryRow {
  label: string
  value: string
}

/** A chosen set in words: its size and the first three names, „und n weitere“ for the rest. */
export function listSummary(label: string, names: string[], emptyText: string): SummaryRow {
  if (names.length === 0) return { label, value: emptyText }
  const shown = names.slice(0, 3).join(', ')
  const rest = names.length - 3
  return { label, value: `${names.length} · ${shown}${rest > 0 ? ` und ${rest} weitere` : ''}` }
}

/** The folders of a one-per-line field; „/“ alone reads everything. */
export function folderSummary(lines: string): SummaryRow {
  const folders = lines
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '' && line !== '/')
  return listSummary('Ordner', folders, 'Alle Ordner (/)')
}
