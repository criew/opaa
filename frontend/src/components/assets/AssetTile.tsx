import { useId, useRef, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined'
import PersonOutlinedIcon from '@mui/icons-material/PersonOutlined'
import PublicIcon from '@mui/icons-material/Public'
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked'
import StarIcon from '@mui/icons-material/Star'
import StarBorderIcon from '@mui/icons-material/StarBorder'
import { blue } from '../../theme/tokens'
import { catalogStatusLabel } from '../../utils/labels'
import { assetTypeDefinition, type AssetTypeDefinition } from './assetTypeRegistry'
import type { AssetTileData, AssetTileStatus } from './assetTileData'

/** The tile opens the asset: its title is a link whose click area covers the tile. */
interface LinkMode {
  kind: 'link'
  to: string
}

/** The tile is a checkbox: a click anywhere but on its own controls toggles the choice. */
interface SelectMode {
  kind: 'select'
  selected: boolean
  onToggle: () => void
  /** A change of this tile is underway: announced as unavailable, yet it keeps the focus. */
  busy?: boolean
  /** Shows the choice without letting it change. */
  readOnly?: boolean
}

interface AssetTileProps {
  tile: AssetTileData
  mode: LinkMode | SelectMode
  /** Sets the caller's own favorite; without it the tile shows no star. */
  onFavoriteChange?: (favorite: boolean) => Promise<void>
  /** Further controls beside the star, e.g. the catalog's "⋯" menu. */
  actions?: ReactNode
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/** Only a state that needs attention carries a dot. */
const STATUS_DOT: Record<AssetTileStatus, string> = {
  UPDATING: 'info.main',
  UPDATE_FAILED: 'error.main',
  NOT_YET_AVAILABLE: 'text.disabled',
  SUCCESSION_OPEN: 'warning.main',
}

/** A state that needs attention, in words behind a coloured dot - the colour never in the text. */
function StateDotLine({ status }: { status: AssetTileStatus }) {
  return (
    <Box
      component="span"
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, fontSize: 11.5 }}
    >
      <Box
        component="span"
        aria-hidden
        sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: STATUS_DOT[status] }}
      />
      <Typography component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
        {catalogStatusLabel(status)}
      </Typography>
    </Box>
  )
}

function MetaLine({ id, children }: { id?: string; children: ReactNode }) {
  return (
    <Typography id={id} component="span" sx={{ fontSize: 11.5, color: 'text.secondary' }}>
      {children}
    </Typography>
  )
}

/** The responsible party behind a person or group symbol. */
function ResponsibleLine({ responsible }: { responsible: { label: string; group: boolean } }) {
  const Icon = responsible.group ? GroupsOutlinedIcon : PersonOutlinedIcon
  return (
    <Box
      component="span"
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, minWidth: 0 }}
    >
      <Icon
        titleAccess={responsible.group ? 'Zuständige Gruppe' : 'Zuständige Person'}
        sx={{ fontSize: 15, color: 'text.secondary' }}
      />
      <Typography component="span" noWrap sx={{ fontSize: 11.5, color: 'text.secondary' }}>
        {responsible.label}
      </Typography>
    </Box>
  )
}

/** Only an asset released to all accounts carries the globe; a restricted one carries nothing. */
function PublicMark() {
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
function TypeBadge({ definition }: { definition: AssetTypeDefinition }) {
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
 * The caller's own favorite mark (ADR-0039, Entscheidung 7): a toggle beside the tile's main
 * control, never inside it. Its name says what the next press does and thereby the state. While a
 * press is pending it is only `aria-disabled`: a natively disabled button would drop the focus.
 */
function FavoriteToggle({
  name,
  favorite,
  onChange,
}: {
  name: string
  favorite: boolean
  onChange: (favorite: boolean) => Promise<void>
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
      sx={{ position: 'relative', zIndex: 1, m: -0.75, ml: 'auto' }}
    >
      {favorite ? (
        <StarIcon sx={{ fontSize: 20, color: 'primary.main' }} />
      ) : (
        <StarBorderIcon sx={{ fontSize: 20 }} />
      )}
    </IconButton>
  )
}

/** Stretches the tile's main control over the whole tile; its own controls stay above it. */
const stretchedSx = {
  '&::after': { content: '""', position: 'absolute', inset: 0, borderRadius: '16px' },
  '&:focus-visible': { outline: 'none' },
  '&:focus-visible::after': { outline: 2, outlineColor: 'primary.main', outlineOffset: 2 },
} as const

/**
 * The one tile of an asset - in the catalog and in every choice of assets. The type badge,
 * the star and further actions are separate controls above the tile's main control, never inside
 * it: in the catalog that is the title as a link, in a choice a checkbox named by the title. A
 * click on the star therefore never changes the choice.
 */
export default function AssetTile({ tile, mode, onFavoriteChange, actions }: AssetTileProps) {
  const definition = assetTypeDefinition(tile.assetType)
  const selecting = mode.kind === 'select'
  const selected = selecting && mode.selected
  const readOnly = selecting && Boolean(mode.readOnly)
  const idBase = useId()
  const descriptionId = `${idBase}-description`
  const figuresId = `${idBase}-figures`
  const noteId = `${idBase}-note`
  // The checkbox is named by the title alone; the rest of the tile is its description.
  const describedBy = [
    tile.description && descriptionId,
    tile.figures && figuresId,
    tile.note && noteId,
  ]
    .filter(Boolean)
    .join(' ')

  const title = (
    <Typography component="span" sx={{ fontSize: 16.5, fontWeight: 600 }}>
      {tile.name}
    </Typography>
  )

  return (
    <Box
      sx={{
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
        textAlign: 'left',
        gap: 1,
        p: 2.5,
        border: selected ? 2 : 1,
        // Keeps the content in place when the border thickens.
        m: selected ? '-1px' : 0,
        borderColor: selected ? 'primary.main' : 'divider',
        borderRadius: '16px',
        bgcolor: selected
          ? (theme) =>
              theme.palette.mode === 'dark' ? alpha(theme.palette.primary.main, 0.16) : blue[50]
          : 'background.paper',
        cursor: readOnly ? 'default' : 'pointer',
        '&:hover': readOnly ? {} : { borderColor: selected ? 'primary.main' : blue[300] },
        '&:focus-within': { borderColor: selected ? 'primary.main' : blue[300] },
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        {/* Chosen shows as check mark and border, never by colour alone (guidelines 5.11). */}
        {selecting &&
          (selected ? (
            <CheckCircleIcon aria-hidden sx={{ fontSize: 18, color: 'primary.main' }} />
          ) : (
            <RadioButtonUncheckedIcon aria-hidden sx={{ fontSize: 18, color: 'text.disabled' }} />
          ))}
        {definition && <TypeBadge definition={definition} />}
        {tile.isPublic && <PublicMark />}
        {onFavoriteChange && tile.favorite !== undefined ? (
          <FavoriteToggle name={tile.name} favorite={tile.favorite} onChange={onFavoriteChange} />
        ) : (
          <Box sx={{ ml: 'auto' }} />
        )}
        {actions}
      </Box>
      {mode.kind === 'link' ? (
        <Box
          component={RouterLink}
          to={mode.to}
          sx={{ color: 'inherit', textDecoration: 'none', ...stretchedSx }}
        >
          {title}
        </Box>
      ) : (
        <Box
          component="button"
          type="button"
          role="checkbox"
          data-asset-tile={`${tile.assetType}:${tile.assetId}`}
          aria-checked={selected}
          aria-describedby={describedBy || undefined}
          aria-disabled={mode.busy || undefined}
          aria-readonly={readOnly || undefined}
          onClick={() => {
            if (!readOnly) mode.onToggle()
          }}
          sx={{
            p: 0,
            border: 0,
            bgcolor: 'transparent',
            color: 'inherit',
            font: 'inherit',
            textAlign: 'left',
            cursor: 'inherit',
            ...stretchedSx,
          }}
        >
          {title}
        </Box>
      )}
      {tile.description && (
        <Typography
          id={descriptionId}
          component="p"
          sx={{
            fontSize: 12.5,
            color: 'text.secondary',
            m: 0,
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
          }}
        >
          {tile.description}
        </Typography>
      )}
      {/* Pushes the figures to the tile's foot; the negative margin takes back the gap. */}
      <Box aria-hidden sx={{ mt: 'auto', mb: -1 }} />
      {tile.figures && <MetaLine id={figuresId}>{tile.figures}</MetaLine>}
      {tile.responsible && <ResponsibleLine responsible={tile.responsible} />}
      {tile.status ? (
        <StateDotLine status={tile.status} />
      ) : (
        tile.updatedAt && <MetaLine>Aktualisiert am {formatDate(tile.updatedAt)}</MetaLine>
      )}
      {tile.successionOpen && <StateDotLine status="SUCCESSION_OPEN" />}
      {tile.note && <MetaLine id={noteId}>{tile.note}</MetaLine>}
    </Box>
  )
}
