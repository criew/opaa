import type { Ref } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'

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
}: {
  entries: SummaryEntry[]
  ref?: Ref<HTMLDivElement>
}) {
  if (entries.length === 0) return null
  return (
    <Box
      ref={ref}
      tabIndex={-1}
      role="alert"
      aria-labelledby="setup-error-summary-title"
      sx={{
        borderLeft: 3,
        borderColor: 'error.main',
        pl: 2,
        py: 1,
        mb: 3,
        outline: 'none',
        '&:focus-visible': { boxShadow: (t) => `0 0 0 3px ${t.palette.primary.main}52` },
      }}
    >
      <Typography
        id="setup-error-summary-title"
        component="h3"
        sx={{ fontSize: 14, fontWeight: 600 }}
      >
        {entries.length === 1
          ? '1 Angabe fehlt oder ist ungültig'
          : `${entries.length} Angaben fehlen oder sind ungültig`}
      </Typography>
      <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
        {entries.map((entry) => (
          <li key={entry.text}>
            <Link
              component="button"
              type="button"
              onClick={entry.onJump}
              sx={{ fontSize: 13.5, textAlign: 'left' }}
            >
              {entry.text}
            </Link>
          </li>
        ))}
      </Box>
    </Box>
  )
}
