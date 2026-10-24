import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import type { SummaryRow } from './sourceKit/summaries'

export interface ReviewSection {
  title: string
  rows: SummaryRow[]
  /** Opens the step the section's values are entered in. */
  onChange: () => void
}

/**
 * The step „Prüfen“ (Prüfliste #32): everything the library will be created with, one section per
 * step, each with „Ändern“. „Ändern“ opens the step; its primary button then leads straight back
 * here.
 */
export default function CreateReviewStep({ sections }: { sections: ReviewSection[] }) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4, maxWidth: 640 }}>
      {sections.map((section) => (
        <Box component="section" key={section.title}>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'baseline',
              justifyContent: 'space-between',
              gap: 2,
              borderBottom: 1,
              borderColor: 'divider',
              pb: 0.5,
              mb: 1,
            }}
          >
            <Typography
              component="h3"
              sx={{ fontSize: 16, fontWeight: 600, color: 'text.primary' }}
            >
              {section.title}
            </Typography>
            <Button size="small" onClick={section.onChange} sx={{ flexShrink: 0 }}>
              Ändern
              <Box component="span" sx={visuallyHidden}>
                {` ${section.title}`}
              </Box>
            </Button>
          </Box>
          <Box
            component="dl"
            sx={{
              m: 0,
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '180px 1fr' },
              columnGap: 2,
              rowGap: { xs: 0.25, sm: 1 },
            }}
          >
            {section.rows.map((row) => (
              <Box key={row.label} sx={{ display: 'contents' }}>
                <Box
                  component="dt"
                  sx={{ fontSize: 13, color: 'text.secondary', pt: { xs: 1, sm: 0 } }}
                >
                  {row.label}
                </Box>
                <Box
                  component="dd"
                  sx={{ m: 0, fontSize: 14, overflowWrap: 'anywhere', color: 'text.primary' }}
                >
                  {row.value || '—'}
                </Box>
              </Box>
            ))}
          </Box>
        </Box>
      ))}
    </Box>
  )
}
