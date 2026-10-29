import { useRef, useState } from 'react'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { type SxProps, type Theme } from '@mui/material/styles'
import DeleteOutlinedIcon from '@mui/icons-material/DeleteOutlined'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import PublicIcon from '@mui/icons-material/Public'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import { outlinedBadgeSx } from '@/shared/ui/badgeStyles'
import type { AssetTypeDefinition } from '../../../shared/assetTypes'

/**
 * Only an asset released to all accounts carries the globe; a restricted one carries nothing.
 * Raised, so a stretched click area around it cannot swallow the pointer its tooltip waits for.
 */
export function PublicMark() {
  const label = 'Für alle Konten freigegeben'
  return (
    <Tooltip title={label}>
      <PublicIcon
        titleAccess={label}
        aria-label={label}
        sx={{ position: 'relative', zIndex: 1, fontSize: 16, color: 'text.secondary' }}
      />
    </Tooltip>
  )
}

/** A private library: lock and the word, so the mark never rests on colour alone. */
export function PrivateMark() {
  return (
    <Typography
      component="span"
      role="img"
      aria-label="Private Bibliothek – nur Sie sehen sie"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.5,
        alignSelf: 'flex-start',
        ...outlinedBadgeSx,
        px: 0.75,
        py: 0.25,
      }}
    >
      <LockOutlinedIcon aria-hidden sx={{ fontSize: 13 }} />
      Privat
    </Typography>
  )
}

/**
 * A private library marked for erasure: icon and word in the quiet frame of every label - the
 * signal colour marks only the symbol (guidelines 2.3, 5.5).
 */
export function ErasingMark() {
  return (
    <Typography
      component="span"
      role="img"
      aria-label="Wird gelöscht"
      sx={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 0.5,
        alignSelf: 'flex-start',
        ...outlinedBadgeSx,
        px: 0.75,
        py: 0.25,
      }}
    >
      <DeleteOutlinedIcon aria-hidden sx={{ fontSize: 13, color: 'error.main' }} />
      Wird gelöscht
    </Typography>
  )
}

/** The type badge: icon and type name, quiet like the role badges. */
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
        ...outlinedBadgeSx,
        px: 0.75,
        py: 0.25,
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
