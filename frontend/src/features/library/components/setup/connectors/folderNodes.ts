import { displayPath } from '../paths'
import { listSummary } from './common'
import type { RowSummary } from '../setupTypes'

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
