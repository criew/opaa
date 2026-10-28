import { useId, useMemo } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import SearchIcon from '@mui/icons-material/Search'
import { Link as RouterLink } from 'react-router'
import type { SpaceListResponse } from '@/shared/api/types'
import CollapsibleSectionHead from '@/shared/ui/CollapsibleSectionHead'
import { useSectionCollapsed } from '@/shared/stores/uiStore'
import { spacesByRecentUse, useRecentSpaceIds } from '@/features/space'

interface SidebarSpaceListProps {
  spaces: SpaceListResponse[]
  activeSpaceId: string | null
  isLoading: boolean
  /** Folded, `keepActive` still shows the active space's row; otherwise nothing but the head. */
  keepActive?: boolean
  showCount?: boolean
  /** How many spaces are rows; the rest are behind the search button. */
  rowCount?: number
}

/** Picking a space lands on an empty chat in it; an archived space accepts none, so its overview. */
function spaceTarget(space: SpaceListResponse): string {
  return space.archived ? `/spaces/${space.id}` : `/spaces/${space.id}/chats/new`
}

/**
 * The most used spaces as rows of the space column: the most recently used ones,
 * filled up in list order, shown personal space first and then by name; the active space is
 * always among them. Plus creates a space, the search button opens the overview with its search
 * field.
 */
export default function SidebarSpaceList({
  spaces,
  activeSpaceId,
  isLoading,
  keepActive = false,
  showCount = false,
  rowCount = 5,
}: SidebarSpaceListProps) {
  const regionId = useId()
  const headingId = useId()
  const collapsed = useSectionCollapsed('spaces')
  const recentSpaceIds = useRecentSpaceIds()

  const rows = useMemo(() => {
    // An archived space only fills a row when it was used recently.
    const candidates = spaces.filter(
      (space) => !space.archived || recentSpaceIds.includes(space.id),
    )
    const ordered = spacesByRecentUse(candidates, recentSpaceIds, rowCount)
    const active = spaces.find((space) => space.id === activeSpaceId)
    const picked =
      !active || ordered.some((space) => space.id === active.id)
        ? ordered
        : [...ordered.slice(0, rowCount - 1), active]
    // Use picks the rows, a stable order shows them: a clicked row must not jump to the top
    // under the pointer. The personal space leads, the others follow by name.
    return [...picked].sort(
      (a, b) => Number(b.isDefault) - Number(a.isDefault) || a.name.localeCompare(b.name, 'de'),
    )
  }, [spaces, recentSpaceIds, activeSpaceId, rowCount])

  const activeRow = rows.find((space) => space.id === activeSpaceId)

  function renderRow(space: SpaceListResponse) {
    const active = space.id === activeSpaceId
    return (
      <ListItem key={space.id} disablePadding>
        <ListItemButton
          component={RouterLink}
          to={spaceTarget(space)}
          selected={active}
          aria-current={active ? 'true' : undefined}
          sx={{ borderRadius: '6px', mb: 0.25, py: 0.5, px: 1, gap: 1 }}
        >
          <Box
            aria-hidden
            sx={{
              width: 20,
              height: 20,
              flexShrink: 0,
              borderRadius: '4px',
              border: 1,
              borderColor: active ? 'primary.main' : 'divider',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 11,
              fontWeight: 600,
              color: active ? 'primary.main' : 'text.secondary',
            }}
          >
            {space.name.trim().charAt(0).toUpperCase()}
          </Box>
          <ListItemText
            primary={space.name}
            slotProps={{
              primary: {
                noWrap: true,
                variant: 'body2',
                sx: { fontWeight: active ? 500 : 400 },
              },
            }}
          />
          {space.archived && (
            <Typography
              component="span"
              sx={{ fontSize: 11, color: 'text.secondary', flexShrink: 0 }}
            >
              archiviert
            </Typography>
          )}
        </ListItemButton>
      </ListItem>
    )
  }

  return (
    <Box>
      <CollapsibleSectionHead
        sectionKey="spaces"
        label="Spaces"
        count={showCount ? spaces.length : undefined}
        controls={regionId}
        component="h2"
        id={headingId}
        actions={
          <>
            <Tooltip title="Neuen Space anlegen">
              <IconButton
                component={RouterLink}
                to="/spaces/new"
                size="small"
                aria-label="Neuen Space anlegen"
                sx={{ p: 0.5, borderRadius: '4px' }}
              >
                <AddIcon sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
            <Tooltip title="Alle Spaces anzeigen und durchsuchen">
              <IconButton
                component={RouterLink}
                to="/spaces"
                size="small"
                aria-label="Alle Spaces anzeigen und durchsuchen"
                sx={{ p: 0.5, borderRadius: '4px' }}
              >
                <SearchIcon sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
          </>
        }
      />
      <Box id={regionId} hidden={collapsed}>
        {isLoading && spaces.length === 0 ? (
          <Box sx={{ py: 1, display: 'flex', justifyContent: 'center' }}>
            <CircularProgress size={18} aria-label="Spaces werden geladen" />
          </Box>
        ) : (
          !collapsed &&
          rows.length > 0 && (
            <List aria-labelledby={headingId} sx={{ py: 0 }}>
              {rows.map(renderRow)}
            </List>
          )
        )}
      </Box>
      {/* Folded, the active space stays as context - outside the folded region. */}
      {collapsed && keepActive && activeRow && (
        <List aria-labelledby={headingId} sx={{ py: 0 }}>
          {renderRow(activeRow)}
        </List>
      )}
    </Box>
  )
}
