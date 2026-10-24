import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { SummaryValue } from './setup/SummaryRow'
import { ROW_GRID, TEXT_LINK } from './setup/rowStyles'
import type { RowSummary } from './setup/setupTypes'

export interface ReviewEntry {
  label: string
  value: RowSummary
  /** Absent for what cannot be changed from here. */
  onChange?: () => void
}

/**
 * The step „Prüfen“ before the library is created: every answer in the same quiet rows as the
 * setup, each with „Ändern“, which leads to the answer and back here.
 */
export default function LibraryReviewStep({ entries }: { entries: ReviewEntry[] }) {
  return (
    <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
      {entries.map((entry) => (
        <Box
          component="li"
          key={entry.label}
          sx={{ ...ROW_GRID, py: 2, borderBottom: 1, borderColor: 'divider' }}
        >
          <Typography component="span" sx={{ fontSize: 15, fontWeight: 600 }}>
            {entry.label}
          </Typography>
          <Box sx={{ gridColumn: { xs: '1 / -1', sm: '2' }, gridRow: { xs: '2', sm: '1' } }}>
            <SummaryValue summary={entry.value || null} />
          </Box>
          {entry.onChange && (
            <Link
              component="button"
              type="button"
              underline="hover"
              onClick={entry.onChange}
              sx={{
                ...TEXT_LINK,
                gridColumn: { xs: '2', sm: '3' },
                gridRow: '1',
                justifySelf: 'end',
              }}
            >
              Ändern
              <Box component="span" sx={visuallyHidden}>
                {` ${entry.label}`}
              </Box>
            </Link>
          )}
        </Box>
      ))}
    </Box>
  )
}
