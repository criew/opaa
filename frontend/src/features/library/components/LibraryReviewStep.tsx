import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { TextAction } from '@/shared/ui/DetailSection'
import { SummaryValue } from './setup/SummaryRow'
import { ROW_GRID } from './setup/rowStyles'
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
            <TextAction
              onClick={entry.onChange}
              context={entry.label}
              sx={{ gridColumn: { xs: '2', sm: '3' }, gridRow: '1', justifySelf: 'end' }}
            >
              Ändern
            </TextAction>
          )}
        </Box>
      ))}
    </Box>
  )
}
