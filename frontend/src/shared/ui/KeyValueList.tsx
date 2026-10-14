import type { ReactNode } from 'react'
import Box from '@mui/material/Box'

export interface KeyValueEntry {
  /** The label on the left; stays secondary, the value carries the weight. */
  label: string
  value: ReactNode
  /** Leaves the entry out entirely - shorter than a list of „nicht hinterlegt". */
  hidden?: boolean
}

interface KeyValueListProps {
  entries: KeyValueEntry[]
  /** `mono` for machine values: endpoints, identifiers, keys. */
  valueFont?: 'text' | 'mono'
  sx?: Parameters<typeof Box>[0]['sx']
}

/**
 * Key and value side by side — as a `dl`, not as a table and not as a card.
 *
 * This replaces the many small boxes that used to carry two to four details
 * („Endpunkt", „Modell-Kennung", „letzter Fehler"). Without a frame the association still stays
 * unambiguous: the key is secondary on the left, the value next to it — and for screen readers it
 * is a description list, so the same statement as before.
 */
export default function KeyValueList({ entries, valueFont = 'text', sx }: KeyValueListProps) {
  const sichtbar = entries.filter((entry) => !entry.hidden)
  if (sichtbar.length === 0) return null
  return (
    <Box
      component="dl"
      sx={[
        {
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', sm: 'max-content 1fr' },
          columnGap: 2.5,
          rowGap: 0.5,
          fontSize: 12.5,
          my: 0,
        },
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {sichtbar.map((entry) => (
        <Box key={entry.label} sx={{ display: 'contents' }}>
          <Box component="dt" sx={{ color: 'text.secondary' }}>
            {entry.label}
          </Box>
          <Box
            component="dd"
            sx={{
              m: 0,
              overflowWrap: 'anywhere',
              ...(valueFont === 'mono' ? { fontFamily: 'monospace', fontSize: 12 } : {}),
            }}
          >
            {entry.value}
          </Box>
        </Box>
      ))}
    </Box>
  )
}
