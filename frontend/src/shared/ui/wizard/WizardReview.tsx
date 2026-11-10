import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { TextAction } from '../DetailSection'
import { REVIEW_ROW_GRID } from './reviewRowGrid'

export interface WizardReviewEntry {
  label: string
  /** A plain text, or what renders the answer; empty shows „–“. */
  value: ReactNode
  /** Leads to the step of the answer; absent for what cannot be changed from here. */
  onChange?: () => void
}

/**
 * The step „Prüfen“ of every assistant: each answer in one row with „Ändern“ at a common right
 * edge (guidelines 5.14).
 */
export default function WizardReview({ entries }: { entries: WizardReviewEntry[] }) {
  return (
    <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
      {entries.map((entry) => (
        <Box
          component="li"
          key={entry.label}
          sx={{ ...REVIEW_ROW_GRID, py: 2, borderBottom: 1, borderColor: 'divider' }}
        >
          <Typography component="span" variant="h6">
            {entry.label}
          </Typography>
          <Box
            sx={{
              gridColumn: { xs: '1 / -1', sm: '2' },
              gridRow: { xs: '2', sm: '1' },
              minWidth: 0,
            }}
          >
            {typeof entry.value === 'string' || entry.value == null ? (
              <Typography
                variant="body2"
                sx={{
                  color: entry.value ? 'text.primary' : 'text.secondary',
                  overflowWrap: 'anywhere',
                }}
              >
                {entry.value || '–'}
              </Typography>
            ) : (
              entry.value
            )}
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
