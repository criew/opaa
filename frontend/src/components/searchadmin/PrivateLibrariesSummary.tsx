import Typography from '@mui/material/Typography'
import type { PrivateLibrarySearchSummaryResponse } from '../../types/api'
import { plural } from './format'

/**
 * The private libraries of the organization as one line, without names. Below the minimum group
 * size of their owners the API names only "fewer than N" and no sums - and so does this line.
 */
export default function PrivateLibrariesSummary({
  summary,
}: {
  summary: PrivateLibrarySearchSummaryResponse
}) {
  const masked = summary.libraryCountFewerThan != null
  const parts = masked
    ? [`weniger als ${summary.libraryCountFewerThan} Bibliotheken`]
    : [
        plural(summary.libraryCount ?? 0, 'Bibliothek', 'Bibliotheken'),
        ...(summary.documentCount != null
          ? [
              `${plural(summary.documentCount, 'Dokument', 'Dokumente')}${
                summary.failedDocumentCount
                  ? `, davon ${summary.failedDocumentCount} fehlgeschlagen`
                  : ''
              }`,
            ]
          : []),
        ...(summary.chunkCount != null
          ? [plural(summary.chunkCount, 'Abschnitt', 'Abschnitte')]
          : []),
      ]
  return (
    <Typography variant="body2" sx={{ mt: 1.5 }} data-testid="private-libraries-summary">
      <strong>Private Bibliotheken</strong> (zusammengefasst, ohne Namen): {parts.join(' · ')}
      {masked && ' – unter der Mindestgruppengröße ohne Summen.'}
    </Typography>
  )
}
