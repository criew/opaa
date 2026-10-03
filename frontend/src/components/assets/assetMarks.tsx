import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { alpha, type SxProps, type Theme } from '@mui/material/styles'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import PersonOutlinedIcon from '@mui/icons-material/PersonOutlined'
import PublicIcon from '@mui/icons-material/Public'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import type { AssetTypeDefinition } from './assetTypeRegistry'

/** The responsible party behind a person or group symbol. */
export function ResponsibleLine({
  responsible,
  fontSize = 11.5,
}: {
  responsible: { label: string; group: boolean }
  fontSize?: number
}) {
  const Icon = responsible.group ? GroupsOutlinedIcon : PersonOutlinedIcon
  return (
    <Box
      component="span"
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, minWidth: 0 }}
    >
      <Icon
        titleAccess={responsible.group ? 'Zuständige Gruppe' : 'Zuständige Person'}
        sx={{ fontSize: fontSize + 3.5, color: 'text.secondary' }}
      />
      <Typography component="span" noWrap sx={{ fontSize, color: 'text.secondary' }}>
        {responsible.label}
      </Typography>
    </Box>
  )
}

/** Only an asset released to all accounts carries the globe; a restricted one carries nothing. */
export function PublicMark() {
  const label = 'Für alle Konten freigegeben'
  return (
    <Tooltip title={label}>
      <PublicIcon
        titleAccess={label}
        aria-label={label}
        sx={{ fontSize: 16, color: 'text.secondary' }}
      />
    </Tooltip>
  )
}

/** The type badge: icon and type name, in the accent of the role badges. */
export function TypeBadge({ definition }: { definition: AssetTypeDefinition }) {
  const Icon = definition.Icon
  return (
    <Typography
      component="span"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.5,
        alignSelf: 'flex-start',
        fontSize: 10.5,
        color: 'primary.main',
        border: 1,
        borderColor: (t) => alpha(t.palette.primary.main, 0.4),
        borderRadius: '4px',
        px: 0.75,
        py: 0.25,
        whiteSpace: 'nowrap',
      }}
    >
      <Icon aria-hidden sx={{ fontSize: 13 }} />
      {definition.title}
    </Typography>
  )
}

/**
 * The caller's own favorite mark (ADR-0039, Entscheidung 7), in the catalog and on the detail
 * page alike. Its name says what the next press does and thereby the state. While a press is
 * pending it is only `aria-disabled`: a natively disabled button would drop the focus.
 */
export function FavoriteToggle({
  name,
  favorite,
  onChange,
  sx,
}: {
  name: string
  favorite: boolean
  onChange: (favorite: boolean) => Promise<void>
  sx?: SxProps<Theme>
}) {
  const pending = useRef(false)
  const [busy, setBusy] = useState(false)
  const label = favorite
    ? `„${name}“ aus den Favoriten entfernen`
    : `„${name}“ als Favorit markieren`

  async function toggle() {
    if (pending.current) return
    pending.current = true
    setBusy(true)
    try {
      await onChange(!favorite)
    } finally {
      pending.current = false
      setBusy(false)
    }
  }

  return (
    <IconButton
      size="small"
      aria-label={label}
      aria-disabled={busy || undefined}
      onClick={() => void toggle()}
      sx={sx}
    >
      {favorite ? (
        <StarIcon sx={{ fontSize: 20, color: 'primary.main' }} />
      ) : (
        <StarBorderIcon sx={{ fontSize: 20 }} />
      )}
    </IconButton>
  )
}
