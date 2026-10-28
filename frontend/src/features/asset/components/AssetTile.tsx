import { useId, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked'
import { blue } from '@/shared/theme/tokens'
import { catalogStatusLabel } from './assetLabels'
import { assetTypeDefinition } from '../../../shared/assetTypes'
import type { AssetTileData, AssetTileStatus } from './assetTileData'
import {
  ErasingMark,
  FavoriteToggle,
  PrivateMark,
  PublicMark,
  ResponsibleLine,
  TypeBadge,
} from './assetMarks'

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
  /**
   * Why the asset cannot be chosen anew. An unchosen tile is locked by it: greyed out, announced as
   * unavailable, yet focusable. A chosen one can still be unchosen and names the reason beforehand.
   */
  blockedReason?: string | null
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
function StateDotLine({ id, status }: { id?: string; status: AssetTileStatus }) {
  return (
    <Box
      id={id}
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

/** Stretches the tile's main control over the whole tile; its own controls stay above it. */
const stretchedSx = {
  '&::after': { content: '""', position: 'absolute', inset: 0, borderRadius: '16px' },
  '&:focus-visible': { outline: 'none' },
  '&:focus-visible::after': { outline: 2, outlineColor: 'primary.main', outlineOffset: 2 },
} as const

/** Marks the tile's main control - the title link or the choice checkbox. */
const MAIN_CONTROL = '[data-tile-main]'
/** Marks the reason a tile cannot be chosen anew. */
const REASON = '[data-tile-reason]'

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
  const blockedReason = selecting && !readOnly ? mode.blockedReason : null
  const locked = Boolean(blockedReason) && !selected
  const idBase = useId()
  const descriptionId = `${idBase}-description`
  const figuresId = `${idBase}-figures`
  // A choice asks only what an asset is and how large: no spread, responsibility, date or globe.
  const figures = selecting ? tile.extent : tile.figures
  const statusId = `${idBase}-status`
  const successionId = `${idBase}-succession`
  const noteId = `${idBase}-note`
  const reasonId = `${idBase}-reason`
  // The checkbox is named by the title alone; the rest of the tile is its description, in the
  // order shown.
  const describedBy = [
    tile.description && descriptionId,
    figures && figuresId,
    tile.status && statusId,
    tile.successionOpen && successionId,
    blockedReason && reasonId,
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
        border: 1,
        borderColor: 'divider',
        borderRadius: '16px',
        bgcolor: 'background.paper',
        cursor: locked ? 'not-allowed' : readOnly ? 'default' : 'pointer',
        // Only the main control marks the tile: pointing at or pressing the star or "⋯" marks
        // just that control. The stretched main control covers every other spot of the tile.
        [`&:has(> ${MAIN_CONTROL}:hover)`]: readOnly || locked ? {} : { borderColor: blue[300] },
        [`&:has(> ${MAIN_CONTROL}:focus)`]: { borderColor: blue[300] },
        // Locked, everything but the reason is greyed out; the reason keeps its text contrast.
        ...(locked ? { [`& > :not(${REASON})`]: { opacity: 0.6 } } : {}),
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
        {/* Chosen shows as the filled check mark against the empty circle - a difference of shape,
            never of colour alone (guidelines 5.11); the tile's frame stays the same. */}
        {selecting &&
          (selected ? (
            <CheckCircleIcon aria-hidden sx={{ fontSize: 20, color: 'primary.main' }} />
          ) : (
            <RadioButtonUncheckedIcon aria-hidden sx={{ fontSize: 20, color: 'text.secondary' }} />
          ))}
        {definition && <TypeBadge definition={definition} />}
        {tile.privateLibrary && <PrivateMark />}
        {tile.erasing && <ErasingMark />}
        {tile.isPublic && !selecting && <PublicMark />}
        {onFavoriteChange && tile.favorite !== undefined ? (
          <FavoriteToggle
            name={tile.name}
            favorite={tile.favorite}
            onChange={onFavoriteChange}
            sx={{ position: 'relative', zIndex: 1, m: -0.75, ml: 'auto' }}
          />
        ) : (
          <Box sx={{ ml: 'auto' }} />
        )}
        {actions}
      </Box>
      {mode.kind === 'link' ? (
        <Box
          component={RouterLink}
          to={mode.to}
          data-tile-main=""
          sx={{ color: 'inherit', textDecoration: 'none', ...stretchedSx }}
        >
          {title}
        </Box>
      ) : (
        <Box
          component="button"
          type="button"
          role="checkbox"
          data-tile-main=""
          data-asset-tile={`${tile.assetType}:${tile.assetId}`}
          aria-checked={selected}
          aria-describedby={describedBy || undefined}
          aria-disabled={locked || mode.busy || undefined}
          aria-readonly={readOnly || undefined}
          onClick={() => {
            if (!readOnly && !locked) mode.onToggle()
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
      {figures && <MetaLine id={figuresId}>{figures}</MetaLine>}
      {tile.responsible && !selecting && <ResponsibleLine responsible={tile.responsible} />}
      {tile.status ? (
        <StateDotLine id={statusId} status={tile.status} />
      ) : (
        tile.updatedAt &&
        !selecting && <MetaLine>Aktualisiert am {formatDate(tile.updatedAt)}</MetaLine>
      )}
      {tile.successionOpen && <StateDotLine id={successionId} status="SUCCESSION_OPEN" />}
      {/* Outside the checkbox and never dimmed, so it holds the text contrast (guidelines 5.11). */}
      {blockedReason && (
        <Typography
          id={reasonId}
          component="span"
          data-tile-reason=""
          sx={{ fontSize: 11.5, color: 'warning.main' }}
        >
          {blockedReason}
        </Typography>
      )}
      {tile.note && <MetaLine id={noteId}>{tile.note}</MetaLine>}
    </Box>
  )
}
