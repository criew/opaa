import { useEffect, useRef } from 'react'
import Box from '@mui/material/Box'
import Drawer from '@mui/material/Drawer'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import { Outlet, useLocation } from 'react-router'
import Sidebar from './Sidebar'
import MobileHeader from './MobileHeader'
import AppFooter from './AppFooter'
import SkipLink from '../components/a11y/SkipLink'
import { MAIN_CONTENT_ID } from '../components/a11y/PageHeading'
import IndexingSnackbar from '../components/admin/IndexingSnackbar'
import { useUiStore } from '../stores/uiStore'

export default function AppShell() {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const sidebarOpen = useUiStore((s) => s.sidebarOpen)
  const setSidebarOpen = useUiStore((s) => s.setSidebarOpen)
  const { pathname } = useLocation()
  const previousPathname = useRef(pathname)

  // On every route change, move focus to the new page's heading so screen readers announce it;
  // if the heading is not rendered yet (data still loading), park focus on <main> and let
  // PageHeading pick it up once it mounts. The initial load keeps the browser's default focus.
  useEffect(() => {
    if (previousPathname.current === pathname) return
    previousPathname.current = pathname
    const main = document.getElementById(MAIN_CONTENT_ID)
    const heading = main?.querySelector<HTMLElement>('h1')
    ;(heading ?? main)?.focus()
  }, [pathname])

  return (
    <Box sx={{ display: 'flex', height: '100vh', overflow: 'hidden' }}>
      <SkipLink />
      {isDesktop ? (
        <Sidebar />
      ) : (
        <Drawer
          open={sidebarOpen}
          onClose={() => setSidebarOpen(false)}
          ModalProps={{ keepMounted: true }}
        >
          <Sidebar />
        </Drawer>
      )}

      <Box
        sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', height: '100vh', minWidth: 0 }}
      >
        <MobileHeader />
        <Box
          component="main"
          id={MAIN_CONTENT_ID}
          tabIndex={-1}
          sx={{
            flexGrow: 1,
            display: 'flex',
            flexDirection: 'column',
            minHeight: 0,
            overflow: 'hidden',
            // Focused only programmatically (skip link, route change) - no ring on the region itself.
            '&:focus': { outline: 'none' },
          }}
        >
          <Outlet />
        </Box>
        <AppFooter />
      </Box>

      <IndexingSnackbar />
    </Box>
  )
}
