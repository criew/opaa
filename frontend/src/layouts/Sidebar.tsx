import { useEffect } from 'react'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Divider from '@mui/material/Divider'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import GroupsIcon from '@mui/icons-material/Groups'
import LibraryBooksIcon from '@mui/icons-material/LibraryBooks'
import LogoutIcon from '@mui/icons-material/Logout'
import PersonIcon from '@mui/icons-material/Person'
import SettingsIcon from '@mui/icons-material/Settings'
import WorkspacesIcon from '@mui/icons-material/Workspaces'
import { NavLink, useLocation, useNavigate, useParams } from 'react-router'
import CreateSpaceDialog from '../components/CreateSpaceDialog'
import ChatList from '../components/chat/ChatList'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { spaceRoleLabel } from '../utils/labels'
import { useState } from 'react'

const SIDEBAR_WIDTH = 300

export { SIDEBAR_WIDTH }

export default function Sidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const { spaceId: routeSpaceId } = useParams<{ spaceId?: string }>()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const spaces = useSpaceStore((s) => s.spaces)
  const isLoadingSpaces = useSpaceStore((s) => s.isLoadingList)
  const loadSpaces = useSpaceStore((s) => s.loadSpaces)
  const [spacesOpen, setSpacesOpen] = useState(true)
  const [chatsOpen, setChatsOpen] = useState(true)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)

  useEffect(() => {
    if (spaces.length === 0) {
      void loadSpaces()
    }
  }, [loadSpaces, spaces.length])

  // The chats section follows the space currently shown by the route (space overview, space
  // detail, or an open chat - all of which carry :spaceId), so switching spaces in the overview
  // updates the list immediately instead of waiting for a chat to be opened (#556). Falls back to
  // the user's default space (or their first space) on routes without a :spaceId, e.g. /settings.
  const defaultSpace = spaces.find((space) => space.isDefault) ?? spaces[0]
  const activeChatSpaceId = routeSpaceId ?? defaultSpace?.id ?? null

  return (
    <Box
      sx={{
        width: SIDEBAR_WIDTH,
        height: '100vh',
        display: 'flex',
        flexDirection: 'column',
        bgcolor: 'background.paper',
        borderRight: 1,
        borderColor: 'divider',
      }}
    >
      <Box sx={{ p: 2.5 }}>
        <Typography variant="h6" sx={{ fontWeight: 700 }}>
          OPAA
        </Typography>
        <Typography variant="caption" color="text.secondary">
          KI-Projektassistent
        </Typography>
      </Box>

      <Divider />

      <Box sx={{ p: 2 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 1 }}>
            Spaces
          </Typography>
          <Box sx={{ display: 'flex', alignItems: 'center' }}>
            <IconButton
              size="small"
              onClick={() => setCreateDialogOpen(true)}
              aria-label="Space erstellen"
            >
              <AddIcon fontSize="small" />
            </IconButton>
            <IconButton
              size="small"
              onClick={() => setSpacesOpen((open) => !open)}
              aria-label="Spaces ein- oder ausklappen"
            >
              {spacesOpen ? (
                <ExpandLessIcon fontSize="small" />
              ) : (
                <ExpandMoreIcon fontSize="small" />
              )}
            </IconButton>
          </Box>
        </Box>
        {spacesOpen &&
          (isLoadingSpaces ? (
            <Box sx={{ py: 2, display: 'flex', justifyContent: 'center' }}>
              <CircularProgress size={20} />
            </Box>
          ) : (
            <List sx={{ px: 0, py: 1 }}>
              {spaces.map((space) => {
                const active = location.pathname === `/spaces/${space.id}`
                return (
                  <ListItemButton
                    key={space.id}
                    onClick={() => navigate(`/spaces/${space.id}`)}
                    selected={active}
                    sx={{ borderRadius: 2, mb: 0.5 }}
                  >
                    <ListItemIcon sx={{ minWidth: 36 }}>
                      {space.isDefault ? (
                        <PersonIcon color="primary" fontSize="small" />
                      ) : (
                        <WorkspacesIcon fontSize="small" />
                      )}
                    </ListItemIcon>
                    <ListItemText
                      primary={space.name}
                      secondary={`${space.memberCount} ${space.memberCount === 1 ? 'Mitglied' : 'Mitglieder'}`}
                      slotProps={{ primary: { noWrap: true } }}
                    />
                    <Chip label={spaceRoleLabel(space.userRole)} size="small" variant="outlined" />
                  </ListItemButton>
                )
              })}
            </List>
          ))}
      </Box>

      <Divider />

      <Box sx={{ p: 2 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 1 }}>
            Chats
          </Typography>
          <IconButton
            size="small"
            onClick={() => setChatsOpen((open) => !open)}
            aria-label="Chats ein- oder ausklappen"
          >
            {chatsOpen ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
          </IconButton>
        </Box>
        {chatsOpen &&
          (activeChatSpaceId ? (
            <Box sx={{ mt: 1 }}>
              <ChatList spaceId={activeChatSpaceId} />
            </Box>
          ) : (
            <Typography color="text.secondary" variant="body2" sx={{ mt: 1 }}>
              Kein Space verfügbar.
            </Typography>
          ))}
      </Box>

      <Divider />
      <List sx={{ px: 1.5, py: 1 }}>
        <ListItemButton
          component={NavLink}
          to="/settings"
          selected={location.pathname === '/settings'}
          sx={{ borderRadius: 2 }}
        >
          <ListItemIcon sx={{ minWidth: 36 }}>
            <SettingsIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText primary="Einstellungen" />
        </ListItemButton>
        <ListItemButton
          component={NavLink}
          to="/libraries"
          selected={location.pathname === '/libraries'}
          sx={{ borderRadius: 2 }}
        >
          <ListItemIcon sx={{ minWidth: 36 }}>
            <LibraryBooksIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText primary="Wissensbibliotheken" />
        </ListItemButton>
        {user?.systemRole === 'SYSTEM_ADMIN' && (
          <ListItemButton
            component={NavLink}
            to="/admin/groups"
            selected={location.pathname === '/admin/groups'}
            sx={{ borderRadius: 2 }}
          >
            <ListItemIcon sx={{ minWidth: 36 }}>
              <GroupsIcon fontSize="small" />
            </ListItemIcon>
            <ListItemText primary="Gruppen" />
          </ListItemButton>
        )}
      </List>

      <Box sx={{ flexGrow: 1 }} />

      {user && (
        <>
          <Divider />
          <Box sx={{ p: 2, display: 'flex', alignItems: 'center', gap: 1 }}>
            <Avatar sx={{ width: 32, height: 32, bgcolor: 'primary.main', fontSize: 14 }}>
              {(user.displayName ?? user.email ?? '?')[0].toUpperCase()}
            </Avatar>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="body2" noWrap>
                {user.displayName ?? user.email ?? 'Benutzer'}
              </Typography>
            </Box>
            <IconButton size="small" onClick={logout} aria-label="Abmelden">
              <LogoutIcon fontSize="small" />
            </IconButton>
          </Box>
        </>
      )}

      <Divider />
      <Box sx={{ p: 2.5 }}>
        <Typography variant="body2" color="text.secondary">
          OPAA v0.1.0
        </Typography>
      </Box>

      <CreateSpaceDialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        onCreated={(spaceId) => {
          setCreateDialogOpen(false)
          navigate(`/spaces/${spaceId}`)
        }}
      />
    </Box>
  )
}
