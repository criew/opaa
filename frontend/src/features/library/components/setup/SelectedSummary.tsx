import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import type { SelectionNoun } from './SelectionList'

const COLLAPSED = 5

interface SelectedSummaryProps {
  entries: { id: string; label: string }[]
  noun: SelectionNoun
  onRemove: (id: string) => void
}

/**
 * What is chosen, outside the list or tree: one line per entry with its path and „Entfernen“,
 * the first five and „Alle n anzeigen“ beyond. Keeps choices on closed levels in view.
 */
export default function SelectedSummary({ entries, noun, onRemove }: SelectedSummaryProps) {
  const [all, setAll] = useState(false)
  if (entries.length === 0) return null
  const shown = all ? entries : entries.slice(0, COLLAPSED)
  return (
    <Box sx={{ mt: 2 }}>
      <Typography component="h4" sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>
        Ausgewählt: {entries.length} {entries.length === 1 ? noun.one : noun.many}
      </Typography>
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {shown.map((entry) => (
          <Box
            component="li"
            key={entry.id}
            sx={{ display: 'flex', alignItems: 'center', gap: 1, minHeight: 32, fontSize: 13 }}
          >
            <Box
              component="span"
              title={entry.label}
              sx={{
                flex: 1,
                minWidth: 0,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {entry.label}
            </Box>
            <Button
              size="small"
              variant="text"
              aria-label={`„${entry.label}“ entfernen`}
              onClick={() => onRemove(entry.id)}
            >
              Entfernen
            </Button>
          </Box>
        ))}
      </Box>
      {entries.length > COLLAPSED && (
        <Button size="small" variant="text" sx={{ ml: -1 }} onClick={() => setAll(!all)}>
          {all ? 'Weniger anzeigen' : `Alle ${entries.length} anzeigen`}
        </Button>
      )}
    </Box>
  )
}
