import { useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import TextField from '@mui/material/TextField'
import { TEXT_LINK } from '../setup/rowStyles'

/** Values a list shows at once; from this many on it also gets a search (pruefliste-detail D19). */
const SHOWN = 8

/**
 * The value list of a selection field: up to eight values directly, beyond that a search and
 * „alle n anzeigen“ instead of a wall of chips.
 */
export default function FieldValues({
  count,
  render,
}: {
  count: number
  render: (query: string, limit: number) => ReactNode
}) {
  const [query, setQuery] = useState('')
  const [all, setAll] = useState(false)
  if (count < SHOWN) return <>{render('', count)}</>
  const normalized = query.trim().toLowerCase()
  return (
    <Box sx={{ mt: 1 }}>
      <TextField
        size="small"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        placeholder="Wert"
        slotProps={{ htmlInput: { 'aria-label': 'Werte durchsuchen' } }}
        sx={{ width: { xs: '100%', sm: 240 } }}
      />
      {render(normalized, all || normalized ? count : SHOWN)}
      {!normalized && (
        <Link
          component="button"
          type="button"
          underline="hover"
          onClick={() => setAll((value) => !value)}
          sx={{ ...TEXT_LINK, mt: 1 }}
        >
          {all ? 'Weniger anzeigen' : `Alle ${count} anzeigen`}
        </Link>
      )}
    </Box>
  )
}
