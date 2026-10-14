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
 * Schlüssel und Wert nebeneinander (#1608) — als `dl`, nicht als Tabelle und nicht als Karte.
 *
 * Das ist der Ersatz für die vielen kleinen Kästen, die bisher zwei bis vier Angaben trugen
 * („Endpunkt", „Modell-Kennung", „letzter Fehler"). Ohne Rahmen bleibt die Zuordnung trotzdem
 * eindeutig: Der Schlüssel steht sekundär links, der Wert daneben — und für Screenreader ist es
 * eine Beschreibungsliste, also dieselbe Aussage wie vorher.
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
