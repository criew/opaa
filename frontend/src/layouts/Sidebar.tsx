import { useEffect } from 'react'
import Avatar from '@mui/material/Avatar'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
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
import ChatIcon from '@mui/icons-material/Chat'
import DescriptionIcon from '@mui/icons-material/Description'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import LogoutIcon from '@mui/icons-material/Logout'
import PersonIcon from '@mui/icons-material/Person'
import SettingsIcon from '@mui/icons-material/Settings'
import WorkspacesIcon from '@mui/icons-material/Workspaces'
import { NavLink, useLocation, useNavigate } from 'react-router'
import CreateSpaceDialog from '../components/CreateSpaceDialog'
import { useChatStore } from '../stores/chatStore'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { spaceRoleLabel } from '../utils/labels'
import { useState } from 'react'

const SIDEBAR_WIDTH = 300

export { SIDEBAR_WIDTH }

export default function Sidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const clearMessages = useChatStore((state) => state.clearMessages)
  const user = useAuthStore((s) => s.user)
  const mode = useAuthStore((s) => s.mode)
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

  function handleNewChat() {
    clearMessages()
    navigate('/chat')
  }

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
                      {space.kind === 'PERSONAL' ? (
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
        {chatsOpen && (
          <>
            <Button
              variant="outlined"
              startIcon={<AddIcon />}
              fullWidth
              onClick={handleNewChat}
              sx={{ mt: 1, borderRadius: 2, justifyContent: 'flex-start', textTransform: 'none' }}
            >
              Neuer Chat
            </Button>
            <List sx={{ px: 0, pt: 1 }}>
              <ListItemButton
                onClick={() => navigate('/chat')}
                selected={location.pathname === '/chat'}
                sx={{ borderRadius: 2 }}
              >
                <ListItemIcon sx={{ minWidth: 36 }}>
                  <ChatIcon fontSize="small" />
                </ListItemIcon>
                <ListItemText primary="Aktueller Chat" />
              </ListItemButton>
            </List>
          </>
        )}
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
          to="/documents"
          selected={location.pathname === '/documents'}
          sx={{ borderRadius: 2 }}
        >
          <ListItemIcon sx={{ minWidth: 36 }}>
            <DescriptionIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText primary="Dokumente" />
        </ListItemButton>
      </List>

      <Box sx={{ flexGrow: 1 }} />

      {mode !== 'mock' && user && (
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
