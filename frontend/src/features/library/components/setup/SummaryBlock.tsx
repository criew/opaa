import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Typography from '@mui/material/Typography'
import { fontFamily } from '@/shared/theme/tokens'
import type { SummaryRow } from '../sources/types'

interface SummaryBlockProps {
  title: string
  rows: SummaryRow[]
  /** „Ändern“ next to the title; absent where nothing may be changed. */
  onChange?: () => void
  /** The heading level in the surrounding outline. */
  level?: 'h3' | 'h4'
}

/**
 * One section of a configuration as label and value - the step „Prüfen“ and the Reiter „Quelle“
 * show the same blocks in the same order as the setup frame.
 */
export default function SummaryBlock({ title, rows, onChange, level = 'h3' }: SummaryBlockProps) {
  if (rows.length === 0) return null
  return (
    <Box
      component="section"
      sx={{ py: 2, '&:not(:first-of-type)': { borderTop: 1, borderColor: 'divider' } }}
    >
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2, mb: 1 }}>
        <Typography component={level} sx={{ fontSize: 15, fontWeight: 600, flex: 1 }}>
          {title}
        </Typography>
        {onChange && (
          <Link component="button" type="button" onClick={onChange} sx={{ fontSize: 13.5 }}>
            Ändern
            <Box component="span" sx={visuallyHidden}>
              {` ${title}`}
            </Box>
          </Link>
        )}
      </Box>
      <SummaryRows rows={rows} />
    </Box>
  )
}

/** The label and value pairs of a summary as a description list. */
export function SummaryRows({ rows }: { rows: SummaryRow[] }) {
  return (
    <Box
      component="dl"
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: '180px 1fr' },
        columnGap: 2.5,
        rowGap: { xs: 0.25, sm: 0.75 },
        m: 0,
        fontSize: 13.5,
        color: 'text.primary',
      }}
    >
      {rows.map((row) => (
        <Box key={row.label} sx={{ display: 'contents' }}>
          <Box component="dt" sx={{ color: 'text.secondary', mt: { xs: 1, sm: 0 } }}>
            {row.label}
          </Box>
          <Box
            component="dd"
            sx={{
              m: 0,
              overflowWrap: 'anywhere',
              ...(row.mono ? { fontFamily: fontFamily.mono, fontSize: 12.5 } : {}),
            }}
          >
            {row.value}
          </Box>
        </Box>
      ))}
    </Box>
  )
}

const visuallyHidden = {
  border: 0,
  clip: 'rect(0 0 0 0)',
  height: '1px',
  margin: '-1px',
  overflow: 'hidden',
  padding: 0,
  position: 'absolute',
  whiteSpace: 'nowrap',
  width: '1px',
} as const
