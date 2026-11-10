import type { Ref } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { fontSize } from '@/shared/theme/tokens'

export interface SummaryEntry {
  /** Row and field label, e.g. „Verbindung: Adresse fehlt“. */
  text: string
  onJump: () => void
}

/**
 * All issues of a submit at once, above the rows, each a link to its field. The same messages
 * stand at the fields; this list only gathers them. It takes the focus after a failed submit.
 */
export default function ErrorSummary({
  entries,
  ref,
  idPrefix = 'setup',
}: {
  entries: SummaryEntry[]
  ref?: Ref<HTMLDivElement>
  /** Prefix of the title's id; unique per page. */
  idPrefix?: string
}) {
  if (entries.length === 0) return null
  const titleId = `${idPrefix}-error-summary-title`
  return (
    <Box
      ref={ref}
      tabIndex={-1}
      role="alert"
      aria-labelledby={titleId}
      sx={{
        borderLeft: 3,
        borderColor: 'error.main',
        pl: 2,
        py: 1,
        mb: 3,
      }}
    >
      <Typography id={titleId} component="h3" sx={{ fontSize: fontSize.text, fontWeight: 600 }}>
        {entries.length === 1
          ? 'Fehler: 1 Angabe prüfen'
          : `Fehler: ${entries.length} Angaben prüfen`}
      </Typography>
      <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
        {entries.map((entry) => (
          <li key={entry.text}>
            <Link
              component="button"
              type="button"
              onClick={entry.onJump}
              sx={{ fontSize: fontSize.text, textAlign: 'left', minHeight: 24, py: 0.25 }}
            >
              {entry.text}
            </Link>
          </li>
        ))}
      </Box>
    </Box>
  )
}
