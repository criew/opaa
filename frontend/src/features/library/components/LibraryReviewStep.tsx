import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'

export interface ReviewEntry {
  label: string
  value: string
  /** Absent for what cannot be changed from here. */
  onChange?: () => void
}

/**
 * The step „Prüfen“ before the library is created: every answer in the same quiet rows as the
 * setup, each with „Ändern“, which leads to the answer and back here.
 */
export default function LibraryReviewStep({ entries }: { entries: ReviewEntry[] }) {
  return (
    <Box component="dl" sx={{ m: 0, maxWidth: 640 }}>
      {entries.map((entry) => (
        <Box
          key={entry.label}
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr auto', sm: '128px 1fr auto' },
            columnGap: 2,
            rowGap: 0.5,
            alignItems: 'baseline',
            py: 2,
            borderTop: 1,
            borderColor: 'divider',
            '&:last-of-type': { borderBottom: 1, borderColor: 'divider' },
          }}
        >
          <Typography component="dt" sx={{ fontSize: 14, fontWeight: 600 }}>
            {entry.label}
          </Typography>
          <Typography
            component="dd"
            sx={{
              m: 0,
              fontSize: 13.5,
              gridColumn: { xs: '1 / -1', sm: '2' },
              gridRow: { xs: '2', sm: '1' },
              overflowWrap: 'anywhere',
              color: entry.value ? 'text.primary' : 'text.secondary',
            }}
          >
            {entry.value || 'keine Angabe'}
          </Typography>
          {entry.onChange && (
            <Button
              variant="text"
              size="small"
              onClick={entry.onChange}
              sx={{ gridColumn: { xs: '2', sm: '3' }, gridRow: '1', minWidth: 0 }}
            >
              Ändern
              <Box component="span" sx={visuallyHidden}>
                {` ${entry.label}`}
              </Box>
            </Button>
          )}
        </Box>
      ))}
    </Box>
  )
}
