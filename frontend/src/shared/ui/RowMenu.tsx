import { useState } from 'react'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Tooltip from '@mui/material/Tooltip'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'

/**
 * „⋯“ at the right edge of a list row: the one place for removing and the rarer actions of an
 * entry, the same in documents, permissions and spaces. The icon sits on the shared edge.
 */
export default function RowMenu({
  label,
  items,
}: {
  /** The accessible name, e.g. „Aktionen für Space „Bauamt““. */
  label: string
  items: Array<{ label: string; onClick: () => void; danger?: boolean }>
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  return (
    <>
      <Tooltip title="Weitere Aktionen">
        <IconButton
          size="small"
          edge="end"
          aria-label={label}
          aria-haspopup="menu"
          aria-expanded={anchor ? true : undefined}
          onClick={(event) => setAnchor(event.currentTarget)}
        >
          <MoreHorizIcon fontSize="small" />
        </IconButton>
      </Tooltip>
      <Menu
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        {items.map((item) => (
          <MenuItem
            key={item.label}
            onClick={() => {
              setAnchor(null)
              item.onClick()
            }}
            sx={item.danger === false ? undefined : { color: 'error.main' }}
          >
            {item.label}
          </MenuItem>
        ))}
      </Menu>
    </>
  )
}
