import { namesSummary } from './common'

/** „Alle Ordner“ or the chosen paths, at most five named. */
export function folderSummary(text: string): string | null {
  const paths = [
    ...new Set(
      text
        .split('\n')
        .map((line) => line.trim())
        .filter(Boolean),
    ),
  ]
  if (paths.includes('/')) return 'Alle Ordner'
  return namesSummary(paths)
}
