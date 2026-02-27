import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import ChatIcon from '@mui/icons-material/Chat'
import DescriptionIcon from '@mui/icons-material/Description'
import SettingsIcon from '@mui/icons-material/Settings'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useChatStore } from '../stores/chatStore'

const SIDEBAR_WIDTH = 280

const navItems = [
  { label: 'Chat', icon: <ChatIcon />, path: '/chat' },
  { label: 'Documents', icon: <DescriptionIcon />, path: '/documents' },
  { label: 'Settings', icon: <SettingsIcon />, path: '/settings' },
]

export { SIDEBAR_WIDTH }

export default function Sidebar() {
  const location = useLocation()
  const navigate = useNavigate()
  const clearMessages = useChatStore((state) => state.clearMessages)

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

      <Box sx={{ px: 1, pt: 1.5 }}>
        <Button
          variant="outlined"
          startIcon={<AddIcon />}
          fullWidth
          onClick={handleNewChat}
          sx={{ borderRadius: 2, justifyContent: 'flex-start', textTransform: 'none' }}
        >
          New Chat
        </Button>
      </Box>

      <List sx={{ px: 1, py: 1.5 }}>
        {navItems.map((item) => (
          <ListItemButton
            key={item.path}
            component={NavLink}
            to={item.path}
            selected={location.pathname === item.path}
            sx={{ borderRadius: 2, mb: 0.5 }}
          >
            <ListItemIcon sx={{ minWidth: 40 }}>{item.icon}</ListItemIcon>
            <ListItemText primary={item.label} />
          </ListItemButton>
        ))}
      </List>

      <Divider />

      <Box sx={{ px: 2.5, py: 2 }}>
        <Typography variant="overline" color="text.secondary">
          Recent Chats
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          No recent chats
        </Typography>
      </Box>

      <Box sx={{ flexGrow: 1 }} />

      <Divider />
      <Box sx={{ p: 2.5 }}>
        <Typography variant="body2" color="text.secondary">
          OPAA v0.1.0
        </Typography>
      </Box>
    </Box>
  )
}
