import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import KeyValueList from '@/shared/ui/KeyValueList'
import { fontFamily } from '@/shared/theme/tokens'
import type { SourceSummary } from '../sources/types'
import PickedList from './PickedList'
import { countOf } from './pickerTypes'

/** Address, account and options of a source as a description list. */
export function SummaryConnection({ summary }: { summary: SourceSummary }) {
  return (
    <KeyValueList
      entries={summary.connection.map((row) => ({
        label: row.label,
        value: row.mono ? (
          <Box component="span" sx={{ fontFamily: fontFamily.mono, fontSize: 12.5 }}>
            {row.value}
          </Box>
        ) : (
          row.value
        ),
        hidden: row.value === '',
      }))}
    />
  )
}

/** The chosen objects with their full paths, or what an empty selection means. */
export function SummarySelection({ summary, limit }: { summary: SourceSummary; limit?: number }) {
  const selection = summary.selection
  if (!selection) return null
  if (selection.items.length === 0) {
    return <Typography sx={{ fontSize: 13.5 }}>{selection.empty}</Typography>
  }
  return (
    <Box>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
        {countOf(selection.items.length, selection.noun)} ausgewählt
      </Typography>
      <PickedList items={selection.items} rootLabel={selection.rootLabel} limit={limit} />
    </Box>
  )
}
