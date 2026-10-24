import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import { fontFamily } from '@/shared/theme/tokens'
import type { SummaryRow } from './types'

export interface SummarySection {
  title: string
  rows: SummaryRow[]
  /** „Ändern“ of the section; absent where nothing can be changed. */
  onChange?: () => void
  /** Text under the rows, e.g. why a section cannot be changed. */
  note?: string
}

const LIST_PREVIEW = 5

function RowValue({ row }: { row: SummaryRow }) {
  const [all, setAll] = useState(false)
  const valueSx = {
    fontSize: 14,
    overflowWrap: 'anywhere' as const,
    ...(row.mono ? { fontFamily: fontFamily.mono, fontSize: 13 } : {}),
  }
  if (!row.list || row.list.length === 0)
    return <Typography sx={valueSx}>{row.value || '–'}</Typography>
  const shown = all ? row.list : row.list.slice(0, LIST_PREVIEW)
  return (
    <Box>
      <Typography sx={{ fontSize: 14 }}>{row.value}</Typography>
      <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
        {shown.map((item, index) => (
          <Box component="li" key={`${item}-${index}`} sx={{ ...valueSx, py: 0.125 }}>
            {item}
          </Box>
        ))}
      </Box>
      {row.list.length > LIST_PREVIEW && (
        <Button variant="text" size="small" onClick={() => setAll((a) => !a)} sx={{ ml: -1 }}>
          {all ? 'Weniger zeigen' : `und ${row.list.length - LIST_PREVIEW} weitere`}
        </Button>
      )}
    </Box>
  )
}

/**
 * Answers as GOV.UK's summary list: one section per topic, label left and value right (stacked on
 * narrow screens), „Ändern“ on the section's head line. Used by the step „Prüfen“ and the Reiter
 * „Quelle“, so both read the same.
 */
export default function SummaryList({
  sections,
  headingLevel = 'h3',
}: {
  sections: SummarySection[]
  headingLevel?: 'h2' | 'h3'
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4, maxWidth: 720 }}>
      {sections.map((section) => (
        <Box component="section" key={section.title} aria-label={section.title}>
          <Box
            sx={{
              display: 'flex',
              alignItems: 'baseline',
              justifyContent: 'space-between',
              gap: 2,
              borderBottom: 1,
              borderColor: 'divider',
              pb: 1,
            }}
          >
            <Typography component={headingLevel} sx={{ fontSize: 16, fontWeight: 600 }}>
              {section.title}
            </Typography>
            {section.onChange && (
              <Button
                variant="text"
                onClick={section.onChange}
                sx={{ flex: 'none', minHeight: 32 }}
              >
                Ändern<Box component="span" sx={visuallyHidden}>{` ${section.title}`}</Box>
              </Button>
            )}
          </Box>
          <Box component="dl" sx={{ m: 0 }}>
            {section.rows.map((row) => (
              <Box
                key={row.label}
                sx={{
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', sm: '200px 1fr' },
                  columnGap: 3,
                  rowGap: 0.25,
                  py: 1.25,
                  borderBottom: 1,
                  borderColor: 'divider',
                }}
              >
                <Typography component="dt" sx={{ fontSize: 13.5, color: 'text.secondary' }}>
                  {row.label}
                </Typography>
                <Box component="dd" sx={{ m: 0, minWidth: 0 }}>
                  <RowValue row={row} />
                </Box>
              </Box>
            ))}
          </Box>
          {section.note && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
              {section.note}
            </Typography>
          )}
        </Box>
      ))}
    </Box>
  )
}
