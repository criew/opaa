import AppBar from '@mui/material/AppBar'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Toolbar from '@mui/material/Toolbar'
import MenuIcon from '@mui/icons-material/Menu'
import BrandMark from '../components/BrandMark'
import NotificationBell from '../components/notifications/NotificationBell'
import { useUiStore } from '../stores/uiStore'

export default function MobileHeader() {
  const toggleSidebar = useUiStore((s) => s.toggleSidebar)

  return (
    <AppBar
      position="sticky"
      elevation={0}
      sx={{
        display: { xs: 'flex', md: 'none' },
        bgcolor: 'background.paper',
        color: 'text.primary',
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Toolbar>
        <IconButton edge="start" color="inherit" aria-label="Menü öffnen" onClick={toggleSidebar}>
          <MenuIcon />
        </IconButton>
        <Box sx={{ ml: 1 }}>
          <BrandMark logoHeight={24} />
        </Box>
        <Box sx={{ flexGrow: 1 }} />
        <NotificationBell />
      </Toolbar>
    </AppBar>
  )
}
