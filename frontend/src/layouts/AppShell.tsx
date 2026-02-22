import Box from '@mui/material/Box'
import Drawer from '@mui/material/Drawer'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import { Outlet } from 'react-router-dom'
import Sidebar, { SIDEBAR_WIDTH } from './Sidebar'
import MobileHeader from './MobileHeader'
import AdminDrawer from '../components/admin/AdminDrawer'
import AdminDrawerToggle from '../components/admin/AdminDrawerToggle'
import IndexingSnackbar from '../components/admin/IndexingSnackbar'
import { useUiStore } from '../stores/uiStore'

export default function AppShell() {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const sidebarOpen = useUiStore((s) => s.sidebarOpen)
  const setSidebarOpen = useUiStore((s) => s.setSidebarOpen)

  return (
    <Box sx={{ display: 'flex', height: '100vh', overflow: 'hidden' }}>
      {isDesktop ? (
        <Box component="nav" sx={{ width: SIDEBAR_WIDTH, flexShrink: 0 }}>
          <Sidebar />
        </Box>
      ) : (
        <Drawer
          open={sidebarOpen}
          onClose={() => setSidebarOpen(false)}
          ModalProps={{ keepMounted: true }}
          sx={{ '& .MuiDrawer-paper': { width: SIDEBAR_WIDTH } }}
        >
          <Sidebar />
        </Drawer>
      )}

      <Box
        component="main"
        sx={{
          flexGrow: 1,
          display: 'flex',
          flexDirection: 'column',
          height: '100vh',
          overflow: 'hidden',
        }}
      >
        <MobileHeader />
        <Box
          sx={{
            display: { xs: 'none', md: 'flex' },
            justifyContent: 'flex-end',
            px: 1,
            py: 0.5,
          }}
        >
          <AdminDrawerToggle />
        </Box>
        <Outlet />
      </Box>

      <AdminDrawer />
      <IndexingSnackbar />
    </Box>
  )
}
