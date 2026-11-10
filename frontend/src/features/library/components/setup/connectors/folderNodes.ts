import { displayPath } from '../../sourceCore/paths'
import { listSummary } from '../../sourceCore/common'
import type { RowSummary } from '../../sourceCore/setupTypes'

/** „Alle Ordner“ or the chosen paths. */
export function folderSummary(text: string): RowSummary {
  const paths = [
    ...new Set(
      text
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    ),
  ]
  return listSummary(paths.includes('/'), ['Ordner', 'Ordner'], paths.map(displayPath))
}
