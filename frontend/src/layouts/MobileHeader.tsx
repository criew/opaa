import AppBar from '@mui/material/AppBar'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Toolbar from '@mui/material/Toolbar'
import Typography from '@mui/material/Typography'
import MenuIcon from '@mui/icons-material/Menu'
import AdminDrawerToggle from '../components/admin/AdminDrawerToggle'
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
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Toolbar>
        <IconButton edge="start" color="inherit" aria-label="open menu" onClick={toggleSidebar}>
          <MenuIcon />
        </IconButton>
        <Typography variant="h6" fontWeight={700} sx={{ ml: 1 }}>
          OPAA
        </Typography>
        <Box sx={{ flexGrow: 1 }} />
        <AdminDrawerToggle />
      </Toolbar>
    </AppBar>
  )
}
