import { useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import useMediaQuery from '@mui/material/useMediaQuery'
import type { Theme } from '@mui/material/styles'
import Link from '@mui/material/Link'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import DeleteOutlinedIcon from '@mui/icons-material/DeleteOutlined'
import MoreHorizIcon from '@mui/icons-material/MoreHoriz'
import WorkspacesOutlinedIcon from '@mui/icons-material/WorkspacesOutlined'
import type { AssetType } from '@/shared/api/types'
import MetaBadge from '@/shared/ui/MetaBadge'
import AssetHeadlineEditor, { type AssetHeadlineEditorProps } from './AssetHeadlineEditor'
import { FavoriteToggle, PublicMark, TypeBadge } from './assetMarks'
import { TAB_CONTENT_WIDTH } from '@/shared/ui/TabSection'
import { spreadLabel } from './assetTileData'
import { assetTypeDefinition } from '../../../shared/assetTypes'
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
  /** Further actions of the „…“ menu, above „In Space verwenden“. */
  menuItems?: Array<{ label: string; onClick: () => void; disabled?: boolean }>
  /** Right under name and description, e.g. the succession state. */
  note?: ReactNode
  /** Under the figures, e.g. the scope of a source or a running indexing. */
  children?: ReactNode
  /** The spread as a link into the tab that lists the spaces (search of the page). */
  spacesTo?: string
  /** The responsible party as a link into the tab that names it (search of the page). */
  responsibleTo?: string
  /** The type's fixed status band under the figures; it then carries the date instead. */
  band?: ReactNode
  /** A word the type chip carries after the type, e.g. „privat“: one chip, not two. */
  typeSuffix?: string
  /**
   * The type's main action. From 600 px on a button left of the star; below, it moves into „⋯“
   * together with the star, so chips and actions share one line.
   */
  primaryAction?: { label: string; icon: ReactNode; onClick: () => void; disabled?: boolean }
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/**
 * One figure of the head's line: a flat statement („87 Dokumente“), set off from the previous
 * one by a middle dot. No pictogram: it would tell apart nothing the words do not.
 */
export function HeaderFigure({ children }: { icon?: ReactNode; children: ReactNode }) {
  return (
    <Typography
      component="span"
      sx={{
        fontSize: 13,
        color: 'text.secondary',
        whiteSpace: 'nowrap',
        // below 600 px a wrapped line would start with the dot: spacing only there
        '&:not(:first-of-type)::before': {
          content: { xs: 'none', sm: '"·"' },
          mx: 1,
          color: 'text.disabled',
        },
      }}
    >
      {children}
    </Typography>
  )
}

/** A figure that sums up a tab: a link into it, in the form of the band's links. */
function FigureLink({ to, children }: { to?: string; children: ReactNode }) {
  if (!to) return <>{children}</>
  return (
    <Link component={RouterLink} to={to} underline="hover" sx={{ fontWeight: 500 }}>
      {children} ›
    </Link>
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
  menuItems,
}: Pick<
  AssetDetailHeaderProps,
  | 'menuItems'
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
  if (!mayUseInSpace && !onDelete && !menuItems?.length) return null
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
        {menuItems?.map((item) => (
          <MenuItem
            key={item.label}
            disabled={item.disabled}
            onClick={() => {
              setAnchor(null)
              item.onClick()
            }}
          >
            {item.label}
          </MenuItem>
        ))}
        {(menuItems?.length ?? 0) > 0 && (mayUseInSpace || onDelete) && <Divider />}
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
  menuItems,
  note,
  children,
  spacesTo,
  responsibleTo,
  band,
  typeSuffix,
  primaryAction,
}: AssetDetailHeaderProps) {
  const compact = useMediaQuery((theme: Theme) => theme.breakpoints.down('sm'))
  const hasFavorite = favorite !== undefined && Boolean(onFavoriteChange)
  const compactItems = compact
    ? [
        ...(primaryAction
          ? [
              {
                label: primaryAction.label,
                onClick: primaryAction.onClick,
                disabled: primaryAction.disabled,
              },
            ]
          : []),
        ...(hasFavorite
          ? [
              {
                label: favorite ? 'Aus Favoriten entfernen' : 'Als Favorit merken',
                onClick: () => void onFavoriteChange?.(!favorite),
              },
            ]
          : []),
      ]
    : []
  const baseDefinition = assetTypeDefinition(assetType)
  const definition =
    baseDefinition && typeSuffix
      ? { ...baseDefinition, title: `${baseDefinition.title} · ${typeSuffix}` }
      : baseDefinition
  return (
    <Box
      component="header"
      sx={{
        position: 'relative',
        // with a band the head ends at the content edge of the tabs below
        maxWidth: band ? TAB_CONTENT_WIDTH : 'none',
        // no line of its own: the tab bar below draws the one separator
        pb: band ? { xs: 1, sm: 2 } : { xs: 2.5, md: 3 },
        mb: band ? 0 : 3,
        '@keyframes opaaHeroIn': {
          from: { opacity: 0, transform: 'translateY(6px)' },
          to: { opacity: 1, transform: 'none' },
        },
        animation: 'opaaHeroIn 200ms cubic-bezier(0.22, 1, 0.36, 1) both',
        '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
      }}
    >
      <Stack
        direction="row"
        sx={{
          justifyContent: 'space-between',
          alignItems: 'center',
          gap: 1.5,
          flexWrap: { xs: 'nowrap', sm: 'wrap' },
          mb: 1,
          position: 'relative',
          minHeight: 40,
        }}
      >
        <Stack
          direction="row"
          spacing={{ xs: 0.5, sm: 1 }}
          useFlexGap
          // one line below 600 px as well: type, source and role fit; a fourth chip is cut, not wrapped
          sx={{
            alignItems: 'center',
            flexWrap: { xs: 'nowrap', sm: 'wrap' },
            overflow: { xs: 'hidden', sm: 'visible' },
            minWidth: 0,
          }}
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
          sx={{ alignItems: 'center', flexWrap: 'wrap', flexShrink: 0, minHeight: 40 }}
        >
          {primaryAction && !compact && (
            <Button
              variant="outlined"
              size="small"
              startIcon={primaryAction.icon}
              onClick={primaryAction.onClick}
              disabled={primaryAction.disabled}
            >
              {primaryAction.label}
            </Button>
          )}
          {actions}
          {hasFavorite && !compact && onFavoriteChange && favorite !== undefined && (
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
            menuItems={compactItems.length ? [...compactItems, ...(menuItems ?? [])] : menuItems}
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

      {/* one line of figures; a figure that sums up a tab leads into it */}
      <Box
        sx={{
          display: 'flex',
          // one line from 600 px on; below, it wraps where it has to and no figure is cut
          flexWrap: 'wrap',
          alignItems: 'center',
          columnGap: { xs: 2, sm: 0 },
          mt: band ? 1 : 2.5,
          position: 'relative',
          minHeight: 22,
          lineHeight: '22px',
        }}
      >
        <HeaderFigure>{extent}</HeaderFigure>
        {figures}
        {spaceCount != null && (
          <HeaderFigure>
            <FigureLink to={spacesTo}>{spreadLabel(spaceCount)}</FigureLink>
          </HeaderFigure>
        )}
        <HeaderFigure>
          <FigureLink to={responsibleTo}>{responsible.label}</FigureLink>
        </HeaderFigure>
        {updatedAt && !band && <HeaderFigure>Aktualisiert am {formatDate(updatedAt)}</HeaderFigure>}
      </Box>

      {band}
      {children}
    </Box>
  )
}
