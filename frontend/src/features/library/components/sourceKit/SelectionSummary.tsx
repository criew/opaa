import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import SelectionDialog from './SelectionDialog'
import { noun, pathLabel, type SelectionItem, type SelectionSource } from './selection'

/** The summary shows at most this many objects, then „und n weitere“. */
const SHOWN = 5

const ellipsis = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
} as const

/** The first objects of a selection, one per line, with „und n weitere“ below. */
export function SelectionLines({
  items,
  emptyText,
  onShowAll,
}: {
  items: SelectionItem[]
  emptyText: string
  onShowAll?: () => void
}) {
  if (items.length === 0) {
    return <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>{emptyText}</Typography>
  }
  const rest = items.length - SHOWN
  return (
    <Box>
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {items.slice(0, SHOWN).map((item) => (
          <Box component="li" key={item.key} sx={{ display: 'flex', minWidth: 0, py: '2px' }}>
            <Tooltip title={pathLabel(item)} placement="bottom-start" enterDelay={500}>
              <Typography sx={{ ...ellipsis, fontSize: 14 }}>
                {item.name}
                {item.trail.length > 0 && (
                  <Box component="span" sx={{ color: 'text.secondary' }}>
                    {' · in '}
                    {item.trail.map((crumb) => crumb.name).join(' / ')}
                  </Box>
                )}
              </Typography>
            </Tooltip>
          </Box>
        ))}
      </Box>
      {rest > 0 &&
        (onShowAll ? (
          <Button size="small" onClick={onShowAll} sx={{ px: 0.5, minWidth: 0 }}>
            und {rest} weitere
          </Button>
        ) : (
          <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>und {rest} weitere</Typography>
        ))}
    </Box>
  )
}

interface SelectionSummaryProps {
  /** Id of the button, so an error summary can jump to it. */
  id: string
  label: string
  source: SelectionSource
  value: SelectionItem[]
  onChange: (items: SelectionItem[]) => void
  /** The text instead of the list while nothing is chosen. */
  emptyText: string
  error?: string | null
  /** The connection fields are not filled yet: the dialog would only fail. */
  notReady?: string | null
}

/**
 * The form's view of a selection (Variante E): a label, at most five chosen objects plus
 * „und n weitere“ and one button - „Auswählen …“ while nothing is chosen, „Ändern“ after. The
 * choosing itself happens in {@link SelectionDialog}, the same for every source.
 */
export default function SelectionSummary({
  id,
  label,
  source,
  value,
  onChange,
  emptyText,
  error,
  notReady,
}: SelectionSummaryProps) {
  const [open, setOpen] = useState(false)
  const [blocked, setBlocked] = useState(false)
  const errorId = `${id}-error`
  const count = value.length
  return (
    <Box>
      <Typography
        id={`${id}-label`}
        sx={{ display: 'block', fontSize: 12, color: 'text.secondary', mb: '5px' }}
      >
        {label}
      </Typography>
      <Box
        sx={{
          borderLeft: 3,
          borderColor: error ? 'error.main' : 'divider',
          pl: 1.5,
          py: 0.5,
          display: 'flex',
          gap: 1.5,
          alignItems: 'flex-start',
          flexWrap: 'wrap',
        }}
      >
        <Box sx={{ flex: '1 1 200px', minWidth: 0 }}>
          {count > 0 && (
            <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 0.5 }}>
              {count} {noun(source, count)} ausgewählt
            </Typography>
          )}
          <SelectionLines items={value} emptyText={emptyText} onShowAll={() => setOpen(true)} />
        </Box>
        <Button
          id={id}
          variant="outlined"
          onClick={() => {
            if (notReady) {
              setBlocked(true)
              return
            }
            setBlocked(false)
            setOpen(true)
          }}
          aria-describedby={error || (blocked && notReady) ? errorId : undefined}
          aria-invalid={error ? true : undefined}
          sx={{ flexShrink: 0 }}
        >
          {count === 0 ? 'Auswählen …' : 'Ändern'}
        </Button>
      </Box>
      {(error || (blocked && notReady)) && (
        <Typography id={errorId} sx={{ fontSize: 12, color: 'error.main', mt: 0.5 }}>
          {error ?? notReady}
        </Typography>
      )}
      <SelectionDialog
        open={open}
        title={`${source.noun[1]} auswählen`}
        source={source}
        value={value}
        onApply={onChange}
        onClose={() => setOpen(false)}
      />
    </Box>
  )
}

/** The read-only list of a stored selection: the summary lines, all of them in a searchable dialog. */
export function SelectionReadOnly({
  title,
  items,
  emptyText,
}: {
  title: string
  items: SelectionItem[]
  emptyText: string
}) {
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const term = filter.trim().toLowerCase()
  const matching = term
    ? items.filter((item) => pathLabel(item).toLowerCase().includes(term))
    : items
  return (
    <>
      <SelectionLines items={items} emptyText={emptyText} onShowAll={() => setOpen(true)} />
      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>{title}</DialogTitle>
        <DialogContent>
          {items.length >= 7 && (
            <TextField
              size="small"
              fullWidth
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder="Suchen"
              slotProps={{ htmlInput: { 'aria-label': `${title} durchsuchen`, type: 'search' } }}
              sx={{ mb: 1 }}
            />
          )}
          {matching.length === 0 && (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Keine Treffer für „{filter.trim()}“.
            </Typography>
          )}
          <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
            {matching.map((item) => (
              <Box
                component="li"
                key={item.key}
                sx={{
                  py: 0.75,
                  borderBottom: 1,
                  borderColor: 'divider',
                  fontSize: 14,
                  overflowWrap: 'anywhere',
                }}
              >
                {pathLabel(item)}
              </Box>
            ))}
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Schließen</Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
