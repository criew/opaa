import { useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import DeleteOutlinedIcon from '@mui/icons-material/DeleteOutlined'
import EventOutlinedIcon from '@mui/icons-material/EventOutlined'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import WorkspacesOutlinedIcon from '@mui/icons-material/WorkspacesOutlined'
import type { AssetType } from '../../types/api'
import MetaBadge from '../MetaBadge'
import AssetHeadlineEditor, { type AssetHeadlineEditorProps } from './AssetHeadlineEditor'
import { FavoriteToggle, PublicMark, ResponsibleLine, TypeBadge } from './assetMarks'
import { spreadLabel } from './assetTileData'
import { assetTypeDefinition } from './assetTypeRegistry'
import { UseInSpaceDialog } from './UseInSpaceButton'

export interface AssetDetailHeaderProps {
  assetType: AssetType
  assetId: string
  name: string
  description?: string | null
  /** Released to all accounts: the globe beside the type badge. */
  isPublic: boolean
  /** Badges after type badge and globe - source type and own role. */
  badges?: ReactNode
  /** The role shown comes from the system administration's bypass, not from an own grant. */
  administrative?: boolean
  headline: Omit<AssetHeadlineEditorProps, 'name' | 'description' | 'badges'>
  /** What the asset holds, in the words of its type ("12 Prompts"). */
  extent: string
  /** Figures only this type has, after the extent - e.g. storage and last run. */
  figures?: ReactNode
  /** The spread from the catalog; omitted while unknown. */
  spaceCount?: number | null
  responsible: { label: string; group: boolean }
  updatedAt?: string | null
  /** The caller's own mark; without it, or without `onFavoriteChange`, no star. */
  favorite?: boolean
  onFavoriteChange?: (favorite: boolean) => Promise<void>
  /** Offers „In Space verwenden" in „⋯". */
  mayUseInSpace: boolean
  onAssociated?: () => void
  /** Offers „Löschen" in „⋯"; absent without the right to delete. */
  onDelete?: () => void
  /** The menu entry's wording where deleting means more, e.g. „Sofort löschen"; default „Löschen". */
  deleteLabel?: string
  /** Keeps the entry visible but unavailable, e.g. while a deletion is under way. */
  deleteDisabled?: boolean
  /** Further controls left of the star, e.g. starting an indexing run. */
  actions?: ReactNode
  /** Right under name and description, e.g. the succession state. */
  note?: ReactNode
  /** Under the figures, e.g. the scope of a source or a running indexing. */
  children?: ReactNode
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/**
 * One figure of the head's band: a flat statement („87 Dokumente") with a pictogram that
 * carries no text of its own, set off from the next one by a hairline.
 */
export function HeaderFigure({ icon, children }: { icon?: ReactNode; children: ReactNode }) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        pr: 2.25,
        // The line stands on the right: a wrapping band starts every row flush with the page edge.
        borderRight: 1,
        borderColor: 'divider',
        '&:last-of-type': { borderRight: 0, pr: 0 },
      }}
    >
      {icon && (
        <Box aria-hidden sx={{ display: 'flex', color: 'text.disabled' }}>
          {icon}
        </Box>
      )}
      <Typography component="div" sx={{ fontSize: 13, color: 'text.secondary' }}>
        {children}
      </Typography>
    </Box>
  )
}

/** „⋯": „In Space verwenden" for whoever may use it, „Löschen" apart and only with the right. */
function MoreActions({
  assetType,
  assetId,
  name,
  mayUseInSpace,
  onAssociated,
  onDelete,
  deleteLabel = 'Löschen',
  deleteDisabled = false,
}: Pick<
  AssetDetailHeaderProps,
  | 'assetType'
  | 'assetId'
  | 'name'
  | 'mayUseInSpace'
  | 'onAssociated'
  | 'onDelete'
  | 'deleteLabel'
  | 'deleteDisabled'
>) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const [useInSpace, setUseInSpace] = useState(false)
  if (!mayUseInSpace && !onDelete) return null
  const buttonId = `asset-detail-actions-${assetId}`
  const menuId = `${buttonId}-menu`
  return (
    <>
      <Tooltip title="Weitere Aktionen">
        <IconButton
          id={buttonId}
          aria-label="Weitere Aktionen"
          aria-haspopup="menu"
          aria-controls={anchor ? menuId : undefined}
          aria-expanded={anchor ? true : undefined}
          onClick={(event) => setAnchor(event.currentTarget)}
        >
          <MoreHorizIcon />
        </IconButton>
      </Tooltip>
      <Menu
        id={menuId}
        anchorEl={anchor}
        open={anchor !== null}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ list: { 'aria-labelledby': buttonId } }}
      >
        {mayUseInSpace && (
          <MenuItem
            onClick={() => {
              setAnchor(null)
              setUseInSpace(true)
            }}
          >
            <WorkspacesOutlinedIcon aria-hidden sx={{ fontSize: 18, mr: 1.5 }} />
            In Space verwenden
          </MenuItem>
        )}
        {mayUseInSpace && onDelete && <Divider />}
        {onDelete && (
          <MenuItem
            onClick={() => {
              setAnchor(null)
              onDelete()
            }}
            disabled={deleteDisabled}
            sx={{ color: 'error.main' }}
          >
            <DeleteOutlinedIcon aria-hidden sx={{ fontSize: 18, mr: 1.5 }} />
            {deleteLabel}
          </MenuItem>
        )}
      </Menu>
      {mayUseInSpace && (
        <UseInSpaceDialog
          assetType={assetType}
          assetId={assetId}
          name={name}
          open={useInSpace}
          onClose={() => setUseInSpace(false)}
          onAssociated={onAssociated}
        />
      )}
    </>
  )
}

/**
 * The head of every asset detail page: type, reach and role on one line with star and
 * „⋯" at its end, name and description editable behind the pencil, and the figures the catalog
 * tile shows - extent, spread, responsibility, last change. The type's own figures and states
 * come in through slots, so every type keeps the same order.
 */
export default function AssetDetailHeader({
  assetType,
  assetId,
  name,
  description,
  isPublic,
  badges,
  administrative = false,
  headline,
  extent,
  figures,
  spaceCount,
  responsible,
  updatedAt,
  favorite,
  onFavoriteChange,
  mayUseInSpace,
  onAssociated,
  onDelete,
  deleteLabel,
  deleteDisabled,
  actions,
  note,
  children,
}: AssetDetailHeaderProps) {
  const definition = assetTypeDefinition(assetType)
  const ExtentIcon = definition?.Icon
  return (
    <Box
      component="header"
      sx={(theme) => ({
        position: 'relative',
        borderBottom: 1,
        borderColor: 'divider',
        pb: { xs: 2.5, md: 3 },
        mb: 3,
        // A restrained accent glow as the page's atmosphere, running out into the page ground.
        '&::before': {
          content: '""',
          position: 'absolute',
          top: -24,
          left: { xs: -20, md: -56 },
          right: 0,
          bottom: 0,
          pointerEvents: 'none',
          background: `radial-gradient(560px 240px at 0% 0%, ${alpha(theme.palette.primary.main, 0.1)}, transparent 72%)`,
        },
        '@keyframes opaaHeroIn': {
          from: { opacity: 0, transform: 'translateY(6px)' },
          to: { opacity: 1, transform: 'none' },
        },
        animation: 'opaaHeroIn 200ms cubic-bezier(0.22, 1, 0.36, 1) both',
        '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
      })}
    >
      <Stack
        direction="row"
        sx={{
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 1.5,
          flexWrap: 'wrap',
          mb: 1,
          position: 'relative',
        }}
      >
        <Stack
          direction="row"
          spacing={1}
          useFlexGap
          sx={{ alignItems: 'center', flexWrap: 'wrap', minWidth: 0 }}
        >
          {definition && <TypeBadge definition={definition} />}
          {isPublic && <PublicMark />}
          {badges}
          {administrative && <MetaBadge>administrativ</MetaBadge>}
        </Stack>
        <Stack
          direction="row"
          spacing={1}
          useFlexGap
          sx={{ alignItems: 'center', flexWrap: 'wrap', flexShrink: 0 }}
        >
          {actions}
          {favorite !== undefined && onFavoriteChange && (
            <FavoriteToggle name={name} favorite={favorite} onChange={onFavoriteChange} />
          )}
          <MoreActions
            assetType={assetType}
            assetId={assetId}
            name={name}
            mayUseInSpace={mayUseInSpace}
            onAssociated={onAssociated}
            onDelete={onDelete}
            deleteLabel={deleteLabel}
            deleteDisabled={deleteDisabled}
          />
        </Stack>
      </Stack>

      <Box sx={{ position: 'relative' }}>
        <AssetHeadlineEditor
          // A fresh editor per asset, so a draft never survives a change of asset.
          key={`headline-${assetId}`}
          name={name}
          description={description}
          {...headline}
        />
        {note && <Box sx={{ maxWidth: 640 }}>{note}</Box>}
      </Box>

      <Stack
        direction="row"
        spacing={2.25}
        useFlexGap
        sx={{ flexWrap: 'wrap', rowGap: 1, mt: 2.5, position: 'relative' }}
      >
        <HeaderFigure icon={ExtentIcon ? <ExtentIcon sx={{ fontSize: 16 }} /> : undefined}>
          {extent}
        </HeaderFigure>
        {figures}
        {spaceCount != null && (
          <HeaderFigure icon={<WorkspacesOutlinedIcon sx={{ fontSize: 16 }} />}>
            {spreadLabel(spaceCount)}
          </HeaderFigure>
        )}
        <HeaderFigure>
          <ResponsibleLine responsible={responsible} fontSize={13} />
        </HeaderFigure>
        {updatedAt && (
          <HeaderFigure icon={<EventOutlinedIcon sx={{ fontSize: 16 }} />}>
            Aktualisiert am {formatDate(updatedAt)}
          </HeaderFigure>
        )}
      </Stack>

      {children}
    </Box>
  )
}
