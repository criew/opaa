import { Suspense, useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Drawer from '@mui/material/Drawer'
import useMediaQuery from '@mui/material/useMediaQuery'
import { useTheme } from '@mui/material/styles'
import { NavigationType, Outlet, useLocation, useNavigationType } from 'react-router'
import GlobalRail, { RAIL_WIDTH } from './GlobalRail'
import { isGlobalAreaPath } from '@/shared/layouts/globalArea'
import Sidebar, { SIDEBAR_WIDTH } from './Sidebar'
import MobileHeader from './MobileHeader'
import SkipLink from '@/shared/ui/a11y/SkipLink'
import { MAIN_CONTENT_ID } from '@/shared/ui/a11y/PageHeading'
import { keepsFocus } from '@/shared/ui/a11y/routeFocus'
import { IndexingSnackbar } from '@/features/search-admin'
import ConfirmHost from '@/shared/ui/feedback/ConfirmHost'
import NotificationHost from '@/shared/ui/feedback/NotificationHost'
import { useUiStore } from '@/shared/stores/uiStore'
import { SpaceListContext } from '@/features/asset'
import { loadSpaceList } from '../themeSeams'
import PageLoading from './PageLoading'

export default function AppShell() {
  const theme = useTheme()
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'))
  const sidebarOpen = useUiStore((s) => s.sidebarOpen)
  const setSidebarOpen = useUiStore((s) => s.setSidebarOpen)
  const { pathname, state: locationState } = useLocation()
  // The marker stays in its history entry; back/forward to that entry is a page change again.
  const keepFocus = useNavigationType() !== NavigationType.Pop && keepsFocus(locationState)
  const previousPathname = useRef(pathname)
  // Mockup 2b: global areas drop the space column; the rail and the area's own light
  // frame carry the navigation there.
  const isGlobalArea = isGlobalAreaPath(pathname)

  // On every route change, move focus to the new page's heading so screen readers announce it;
  // if the heading is not rendered yet (data still loading), park focus on <main> and let
  // PageHeading pick it up once it mounts. The initial load and a navigation that keeps the view
  // (see KEEP_FOCUS_STATE) leave the focus where it is.
  useEffect(() => {
    if (previousPathname.current === pathname) return
    previousPathname.current = pathname
    if (keepFocus) return
    const main = document.getElementById(MAIN_CONTENT_ID)
    const heading = main?.querySelector<HTMLElement>('h1')
    ;(heading ?? main)?.focus()
  }, [pathname, keepFocus])

  // A route change closes the drawer in the very render that shows the new page. The drawer's
  // focus restore (to "Menü öffnen") thus runs before the new page places its focus, so the focus
  // follows the destination; Escape and the backdrop still hand it back to "Menü öffnen".
  const [drawerPathname, setDrawerPathname] = useState(pathname)
  if ((!sidebarOpen || isDesktop) && drawerPathname !== pathname) setDrawerPathname(pathname)
  const drawerOpen = sidebarOpen && drawerPathname === pathname

  // Navigating from inside the drawer must also dismiss it - otherwise it stays on top of the
  // page just navigated to; the rail puts far more destinations into the drawer.
  useEffect(() => {
    if (!isDesktop) setSidebarOpen(false)
  }, [pathname, isDesktop, setSidebarOpen])

  return (
    <Box sx={{ display: 'flex', height: '100vh', overflow: 'hidden' }}>
      <SkipLink />
      {isDesktop ? (
        <>
          <GlobalRail />
          {!isGlobalArea && <Sidebar resizable />}
        </>
      ) : (
        <Drawer
          open={drawerOpen}
          onClose={() => setSidebarOpen(false)}
          ModalProps={{ keepMounted: true }}
          // Rail and space column together; on very narrow screens the column gives way
          // (its flexShrink) rather than the drawer covering the whole viewport. In global
          // areas only the rail remains, so the drawer narrows to it.
          slotProps={{
            paper: {
              // The paper is the drawer's dialog; without a name axe reports aria-dialog-name.
              'aria-label': 'Navigation',
              sx: {
                width: isGlobalArea ? RAIL_WIDTH : RAIL_WIDTH + SIDEBAR_WIDTH,
                maxWidth: '92vw',
              },
            },
          }}
        >
          <Box sx={{ display: 'flex', height: '100%', minWidth: 0 }}>
            <GlobalRail />
            {!isGlobalArea && <Sidebar />}
          </Box>
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
          <SpaceListContext value={loadSpaceList}>
            <Suspense fallback={<PageLoading />}>
              <Outlet />
            </Suspense>
          </SpaceListContext>
        </Box>
      </Box>

      <IndexingSnackbar />
      <NotificationHost />
      <ConfirmHost />
    </Box>
  )
}
