import { useEffect, useMemo } from 'react'
import { CssBaseline, ThemeProvider, useMediaQuery } from '@mui/material'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import ErrorBoundary from './components/ErrorBoundary'
import ProtectedRoute from './components/ProtectedRoute'
import { createAppTheme } from './theme/theme'
import AppShell from './layouts/AppShell'
import ChatPage from './pages/ChatPage'
import ChatRedirect from './pages/ChatRedirect'
import SettingsPage from './pages/SettingsPage'
import LoginPage from './pages/LoginPage'
import AuthCallbackPage from './pages/AuthCallbackPage'
import SpacePage from './pages/SpacePage'
import SpacesOverviewPage from './pages/SpacesOverviewPage'
import SpaceCreatePage from './pages/SpaceCreatePage'
import SpaceManagementPage from './pages/SpaceManagementPage'
import GroupManagementPage from './pages/GroupManagementPage'
import LibraryManagementPage from './pages/LibraryManagementPage'
import LibraryDetailPage from './pages/LibraryDetailPage'
import { useAuthStore } from './stores/authStore'
import { useBrandingStore } from './stores/brandingStore'
import { useUiStore } from './stores/uiStore'
import { resolveThemeMode } from './theme/colorScheme'
import BrandingSettingsPage from './pages/BrandingSettingsPage'

export default function App() {
  const initialize = useAuthStore((s) => s.initialize)
  const themeMode = useUiStore((s) => s.themeMode)
  const branding = useBrandingStore((s) => s.branding)
  const loadBranding = useBrandingStore((s) => s.loadBranding)
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)')

  // The operator's default applies only where the user has made no choice of their own - see
  // resolveThemeMode. `system` then still follows the browser, as it always has.
  const preferredMode = resolveThemeMode(themeMode, branding.defaultColorScheme)
  const effectiveMode =
    preferredMode === 'system' ? (prefersDark ? 'dark' : 'light') : preferredMode
  // The accent is the only branding value the theme itself consumes; product name, claim and logo
  // are read from the store by the components that render them (#583, guidelines 7).
  const theme = useMemo(
    () => createAppTheme(effectiveMode, { primaryColor: branding.primaryColor }),
    [effectiveMode, branding.primaryColor],
  )

  useEffect(() => {
    initialize()
    // Deliberately not awaited and deliberately outside any auth gate: the sign-in page needs the
    // branding too, and the store falls back to the OPAA standard if the request fails (#583).
    void loadBranding()
  }, [initialize, loadBranding])

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <ErrorBoundary>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/auth/callback" element={<AuthCallbackPage />} />
            <Route
              element={
                <ProtectedRoute>
                  <AppShell />
                </ProtectedRoute>
              }
            >
              <Route index element={<Navigate to="/chat" replace />} />
              <Route path="chat" element={<ChatRedirect />} />
              <Route path="spaces/new" element={<SpaceCreatePage />} />
              <Route path="spaces/:spaceId/chats/:chatId" element={<ChatPage />} />
              <Route path="spaces/:spaceId" element={<SpacePage />} />
              <Route path="spaces/:spaceId/manage" element={<SpaceManagementPage />} />
              <Route path="spaces" element={<SpacesOverviewPage />} />
              <Route path="libraries" element={<LibraryManagementPage />} />
              <Route path="libraries/:libraryId" element={<LibraryDetailPage />} />
              <Route path="admin/groups" element={<GroupManagementPage />} />
              <Route path="admin/branding" element={<BrandingSettingsPage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="*" element={<Navigate to="/chat" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ErrorBoundary>
    </ThemeProvider>
  )
}
