import type { SummaryRow } from '../sources/types'

/** A folder list as summary rows: „Alle Ordner“ or one row with the paths. */
export function folderSummary(text: string): SummaryRow[] {
  const folders = [
    ...new Set(
      text
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    ),
  ]
  if (folders.length === 0 || folders.includes('/'))
    return [{ label: 'Ordner', value: 'Alle Ordner' }]
  return [
    {
      label: folders.length === 1 ? 'Ordner' : `Ordner (${folders.length})`,
      value: listed(folders),
      mono: true,
    },
  ]
}

/** Up to five names, then „und n weitere“. */
export function listed(names: string[], max = 5): string {
  if (names.length <= max) return names.join(', ')
  return `${names.slice(0, max).join(', ')} und ${names.length - max} weitere`
}
