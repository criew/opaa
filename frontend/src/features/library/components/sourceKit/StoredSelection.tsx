import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { SelectionReadOnly } from './SelectionSummary'
import type { SelectionItem } from './selection'

/**
 * The Umfang of a stored library on the Reiter „Quelle“ (Variante E): the same lines as the
 * form's summary - at most five, „und n weitere“ opens the whole list with a search - and the one
 * sentence on who sees what was read, identical for every source.
 */
export default function StoredSelection({
  label,
  items,
  emptyText,
}: {
  label: string
  items: SelectionItem[]
  emptyText: string
}) {
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
        {items.length > 0 ? `${label} · ${items.length}` : label}
      </Typography>
      <SelectionReadOnly title={label} items={items} emptyText={emptyText} />
      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
        Was daraus indiziert wird, sehen alle Leseberechtigten dieser Bibliothek.
      </Typography>
    </Box>
  )
}
