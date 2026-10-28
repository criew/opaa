import { useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import type { SxProps, Theme } from '@mui/material/styles'
import Link from '@mui/material/Link'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import { Link as RouterLink } from 'react-router'
import type { AssetType } from '@/shared/api/types'
import MetaBadge from '@/shared/ui/MetaBadge'
import RowMenu from '@/shared/ui/RowMenu'
import { focusRingOffsetPx, focusRingWidthPx } from '@/shared/theme/tokens'
import AssetHeadlineEditor, { type AssetHeadlineEditorProps } from './AssetHeadlineEditor'
import { FavoriteToggle, PublicMark, TypeBadge } from './assetMarks'
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
  /** Figures only this type has, after the extent - e.g. storage. */
  figures?: ReactNode
  /** The spread from the catalog; omitted while unknown. */
  spaceCount?: number | null
  /** Where the spread links to - the tab of the associations. */
  spaceHref?: string
  responsible: { label: string; group: boolean }
  /** Where the responsible party links to - the tab of the permissions. */
  responsibleHref?: string
  updatedAt?: string | null
  /** The type's one state, the last element of the figure line; replaces „Aktualisiert am". */
  status?: ReactNode
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
}

function formatDate(value: string): string {
  return new Date(value).toLocaleDateString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  })
}

/**
 * One figure of the head's single line („87 Dokumente"): plain text that keeps together, set off
 * from the next one by a middle dot. A figure that sums up a tab links into it.
 */
export function HeaderFigure({
  to,
  children,
  sx,
}: {
  to?: string
  children: ReactNode
  sx?: SxProps<Theme>
}) {
  return (
    <Box
      component="span"
      sx={[
        { display: 'inline', whiteSpace: 'nowrap', '&::before': FIGURE_SEPARATOR },
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {to ? (
        // a figure that leads into a tab reads as a link: link colour, its chevron says where it goes
        <Link component={RouterLink} to={to} replace underline="hover" color="primary">
          {children}
          <Box component="span" aria-hidden sx={{ ml: 0.5 }}>
            ›
          </Box>
        </Link>
      ) : (
        children
      )}
    </Box>
  )
}

const SEPARATOR = { content: '"·"', mx: 1, color: 'text.disabled' } as const

/** Leads every figure; the one at the start of a line sits in the clipped gutter of the block. */
const FIGURE_GUTTER = 20
/** How far a focus ring reaches beyond its element (guidelines 4.4). */
const FOCUS_RING_ROOM = focusRingWidthPx + focusRingOffsetPx
const FIGURE_SEPARATOR = {
  content: '"·"',
  display: 'inline-block',
  width: FIGURE_GUTTER,
  textAlign: 'center',
  color: 'text.disabled',
} as const

/** „⋯": „In Space verwenden" for whoever may use it, „Löschen" last and only with the right. */
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
  const [useInSpace, setUseInSpace] = useState(false)
  if (!mayUseInSpace && !onDelete && !menuItems?.length) return null
  return (
    <>
      <RowMenu
        name={name}
        items={[
          ...(menuItems ?? []),
          mayUseInSpace && { label: 'In Space verwenden', onClick: () => setUseInSpace(true) },
          onDelete && {
            label: deleteLabel,
            onClick: onDelete,
            danger: true,
            disabled: deleteDisabled,
          },
        ]}
      />
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
  spaceHref,
  responsible,
  responsibleHref,
  updatedAt,
  status,
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
}: AssetDetailHeaderProps) {
  const definition = assetTypeDefinition(assetType)
  // below 600 px a head with a state shows one line - the state and a toggle - and its figures
  // only on request
  const [figuresOpen, setFiguresOpen] = useState(false)
  const compact = Boolean(status)
  return (
    <Box
      component="header"
      sx={{
        position: 'relative',
        // no line of its own: the tab bar below draws the one separator
        pb: { xs: 1.5, md: 2 },
        mb: { xs: 1.5, md: 2 },
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
          columnGap: 1.5,
          rowGap: 0.75,
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
        {/* below 600 px the actions always take their own line, with or without a main action,
            so the head has the same height for every role */}
        <Stack
          direction="row"
          spacing={1}
          useFlexGap
          sx={{
            alignItems: 'center',
            flexWrap: 'wrap',
            flexShrink: 0,
            flexBasis: { xs: '100%', sm: 'auto' },
            minHeight: 40,
          }}
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
            menuItems={menuItems}
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

      {/* one line at 1280 px; below 600 px the state alone on one line with a toggle, the
          figures below it on request - every figure kept whole, no line starts or ends on a
          separator */}
      <Typography
        component="div"
        sx={{
          mt: { xs: 0.75, sm: 1.5 },
          fontSize: 13,
          lineHeight: '22px',
          color: 'text.secondary',
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'baseline',
          position: 'relative',
        }}
      >
        {/* figures and state in one wrapping row: whatever starts a line has its separator in
            the clipped gutter; below 600 px the state leads on its own line with the toggle */}
        <Box
          component="span"
          sx={{
            display: 'block',
            overflow: 'hidden',
            // The clip box reaches as far out as a focus ring does, so a link's ring stays whole;
            // the padding is taken back by the margin, and the 4 px stay inside the gutter.
            p: `${FOCUS_RING_ROOM}px`,
            m: `-${FOCUS_RING_ROOM}px`,
            maxWidth: `calc(100% + ${2 * FOCUS_RING_ROOM}px)`,
            flexBasis: status
              ? `calc(100% + ${2 * FOCUS_RING_ROOM}px)`
              : { xs: `calc(100% + ${2 * FOCUS_RING_ROOM}px)`, sm: 'auto' },
          }}
        >
          <Box
            component="span"
            id={`head-figures-${assetId}`}
            sx={{
              display: 'flex',
              flexWrap: 'wrap',
              alignItems: 'baseline',
              ml: `-${FIGURE_GUTTER}px`,
              // 24 px lines below 600 px: two links on adjacent lines keep their 24 px target apart
              lineHeight: { xs: '24px', sm: '22px' },
              '& > :not([data-head-status])': {
                display: { xs: compact && !figuresOpen ? 'none' : 'inline', sm: 'inline' },
              },
            }}
          >
            <HeaderFigure>{extent}</HeaderFigure>
            {figures}
            {spaceCount != null && (
              <HeaderFigure to={spaceCount > 0 ? spaceHref : undefined}>
                {spreadLabel(spaceCount)}
              </HeaderFigure>
            )}
            <HeaderFigure to={responsibleHref}>{responsible.label}</HeaderFigure>
            {status && (
              <Box
                component="span"
                data-head-status
                sx={{
                  minHeight: { xs: 28, sm: 22 },
                  display: 'flex',
                  alignItems: 'center',
                  whiteSpace: 'nowrap',
                  order: { xs: -1, sm: 0 },
                  flexBasis: { xs: '100%', sm: 'auto' },
                  // the gutter stays to the left at every width: no separator before a lead
                  pl: { xs: `${FIGURE_GUTTER}px`, sm: 0 },
                  '&::before': { ...FIGURE_SEPARATOR, display: { xs: 'none', sm: 'inline-block' } },
                }}
              >
                {status}
                <IconButton
                  size="small"
                  aria-expanded={figuresOpen}
                  aria-controls={`head-figures-${assetId}`}
                  aria-label={figuresOpen ? 'Kennzahlen ausblenden' : 'Kennzahlen anzeigen'}
                  onClick={() => setFiguresOpen((open) => !open)}
                  sx={{
                    display: { xs: 'inline-flex', sm: 'none' },
                    ml: 'auto',
                    mr: -0.75,
                    p: 0.25,
                  }}
                >
                  <ExpandMoreIcon
                    sx={{ fontSize: 20, transform: figuresOpen ? 'rotate(180deg)' : 'none' }}
                  />
                </IconButton>
              </Box>
            )}
          </Box>
        </Box>
        {!status && updatedAt && (
          <Box component="span" sx={{ height: 22, '&::before': SEPARATOR }}>
            Aktualisiert am {formatDate(updatedAt)}
          </Box>
        )}
      </Typography>

      {children}
    </Box>
  )
}
