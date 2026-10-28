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
import { useTheme } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import SearchIcon from '@mui/icons-material/Search'
import { Link as RouterLink } from 'react-router'
import type { SpaceListResponse } from '@/shared/api/types'
import CollapsibleSectionHead from '@/shared/ui/CollapsibleSectionHead'
import { useSectionCollapsed } from '@/shared/stores/uiStore'
import { darkRoles, lightRoles } from '@/shared/theme/tokens'
import { SPACE_SEARCH_HANDOVER, spacesByRecentUse, useRecentSpaceIds } from '@/features/space'

/** How many spaces the column shows as rows; the rest are behind the search button. */
export const SPACE_ROW_COUNT = 5

interface SidebarSpaceListProps {
  spaces: SpaceListResponse[]
  activeSpaceId: string | null
  isLoading: boolean
}

/** A row leads into an empty chat of its space; an archived space accepts none, so its overview. */
function spaceTarget(space: SpaceListResponse): string {
  return space.archived ? `/spaces/${space.id}` : `/spaces/${space.id}/chats/new`
}

/**
 * The spaces of the fixed space column (guidelines 5.13): up to {@link SPACE_ROW_COUNT} rows,
 * picked by recent use and filled up in list order, shown personal space first and then by name;
 * the active space is always among them and stays visible while the group is folded. The head
 * counts the person's spaces that are not archived; plus creates a space, the search button opens
 * the overview with the cursor in its search field.
 */
export default function SidebarSpaceList({
  spaces,
  activeSpaceId,
  isLoading,
}: SidebarSpaceListProps) {
  const regionId = useId()
  const headingId = useId()
  const collapsed = useSectionCollapsed('spaces')
  const recentSpaceIds = useRecentSpaceIds()
  const roles = useTheme().palette.mode === 'light' ? lightRoles : darkRoles

  const rows = useMemo(() => {
    // An archived space only fills a row when it was used recently.
    const candidates = spaces.filter(
      (space) => !space.archived || recentSpaceIds.includes(space.id),
    )
    const picked = spacesByRecentUse(candidates, recentSpaceIds, SPACE_ROW_COUNT)
    const active = spaces.find((space) => space.id === activeSpaceId)
    const withActive =
      !active || picked.some((space) => space.id === active.id)
        ? picked
        : [...picked.slice(0, SPACE_ROW_COUNT - 1), active]
    // Use picks the rows, a stable order shows them: a clicked row must not jump under the pointer.
    return [...withActive].sort(
      (a, b) => Number(b.isDefault) - Number(a.isDefault) || a.name.localeCompare(b.name, 'de'),
    )
  }, [spaces, recentSpaceIds, activeSpaceId])

  const activeRow = rows.find((space) => space.id === activeSpaceId)
  const count = spaces.filter((space) => !space.archived).length

  function renderRow(space: SpaceListResponse) {
    const active = space.id === activeSpaceId
    return (
      <ListItem key={space.id} disablePadding>
        <ListItemButton
          component={RouterLink}
          to={spaceTarget(space)}
          aria-current={active ? 'true' : undefined}
          sx={{
            borderRadius: '6px',
            mb: 0.25,
            py: 0.5,
            px: 1,
            gap: 1,
            // The active row is the raised card of guidelines 2.3, like the active entry of a
            // global area's column; the others keep a transparent frame so nothing shifts.
            border: 1,
            borderColor: active ? roles.borderStrong : 'transparent',
            bgcolor: active ? roles.bg1 : 'transparent',
            '&:hover': { bgcolor: roles.bg1 },
          }}
        >
          <Box
            aria-hidden
            sx={{
              width: 20,
              height: 20,
              flexShrink: 0,
              borderRadius: '4px',
              border: 1,
              borderColor: active ? 'primary.main' : roles.borderStrong,
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
        count={count}
        controls={regionId}
        component="h2"
        id={headingId}
        actions={
          <>
            <Tooltip title="Neuer Space">
              <IconButton
                component={RouterLink}
                to="/spaces/new"
                size="small"
                aria-label="Neuer Space"
                sx={{ p: 0.5, borderRadius: '4px' }}
              >
                <AddIcon sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
            <Tooltip title="Alle Spaces anzeigen und durchsuchen">
              <IconButton
                component={RouterLink}
                to="/spaces"
                state={SPACE_SEARCH_HANDOVER}
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
      {collapsed && activeRow && (
        <List aria-labelledby={headingId} sx={{ py: 0 }}>
          {renderRow(activeRow)}
        </List>
      )}
    </Box>
  )
}
