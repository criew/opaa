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
import LogoutIcon from '@mui/icons-material/Logout'
import PersonIcon from '@mui/icons-material/Person'
import SettingsIcon from '@mui/icons-material/Settings'
import WorkspacesIcon from '@mui/icons-material/Workspaces'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useChatStore } from '../stores/chatStore'
import { useAuthStore } from '../stores/authStore'
import { useWorkspaceStore } from '../stores/workspaceStore'

const SIDEBAR_WIDTH = 300

export { SIDEBAR_WIDTH }

export default function Sidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const clearMessages = useChatStore((state) => state.clearMessages)
  const user = useAuthStore((s) => s.user)
  const mode = useAuthStore((s) => s.mode)
  const logout = useAuthStore((s) => s.logout)
  const workspaces = useWorkspaceStore((s) => s.workspaces)
  const isLoadingWorkspaces = useWorkspaceStore((s) => s.isLoadingList)
  const loadWorkspaces = useWorkspaceStore((s) => s.loadWorkspaces)

  useEffect(() => {
    if (workspaces.length === 0) {
      void loadWorkspaces()
    }
  }, [loadWorkspaces, workspaces.length])

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
        <Typography variant="h6" fontWeight={700}>
          OPAA
        </Typography>
        <Typography variant="caption" color="text.secondary">
          AI Project Assistant
        </Typography>
      </Box>

      <Divider />

      <Box sx={{ p: 2 }}>
        <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 1 }}>
          Workspaces
        </Typography>
        {isLoadingWorkspaces ? (
          <Box sx={{ py: 2, display: 'flex', justifyContent: 'center' }}>
            <CircularProgress size={20} />
          </Box>
        ) : (
          <List sx={{ px: 0, py: 1 }}>
            {workspaces.map((workspace) => {
              const active = location.pathname === `/workspaces/${workspace.id}`
              return (
                <ListItemButton
                  key={workspace.id}
                  onClick={() => navigate(`/workspaces/${workspace.id}`)}
                  selected={active}
                  sx={{ borderRadius: 2, mb: 0.5 }}
                >
                  <ListItemIcon sx={{ minWidth: 36 }}>
                    {workspace.type === 'PERSONAL' ? (
                      <PersonIcon color="primary" fontSize="small" />
                    ) : (
                      <WorkspacesIcon fontSize="small" />
                    )}
                  </ListItemIcon>
                  <ListItemText
                    primary={workspace.name}
                    secondary={`${workspace.memberCount} member${workspace.memberCount === 1 ? '' : 's'}`}
                    primaryTypographyProps={{ noWrap: true }}
                  />
                  <Chip label={workspace.userRole} size="small" variant="outlined" />
                </ListItemButton>
              )
            })}
          </List>
        )}
      </Box>

      <Divider />

      <Box sx={{ p: 2 }}>
        <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: 1 }}>
          Chats
        </Typography>
        <Button
          variant="outlined"
          startIcon={<AddIcon />}
          fullWidth
          onClick={handleNewChat}
          sx={{ mt: 1, borderRadius: 2, justifyContent: 'flex-start', textTransform: 'none' }}
        >
          New Chat
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
            <ListItemText primary="Current Chat" />
          </ListItemButton>
        </List>
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
          <ListItemText primary="Settings" />
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
          <ListItemText primary="Documents" />
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
                {user.displayName ?? user.email ?? 'User'}
              </Typography>
            </Box>
            <IconButton size="small" onClick={logout} aria-label="sign out">
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
    </Box>
  )
}
