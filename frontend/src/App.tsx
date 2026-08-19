import { useEffect, useMemo } from 'react'
import { CssBaseline, ThemeProvider, useMediaQuery } from '@mui/material'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import ErrorBoundary from './components/ErrorBoundary'
import ProtectedRoute from './components/ProtectedRoute'
import { createAppTheme } from './theme/theme'
import AppShell from './layouts/AppShell'
import ChatPage from './pages/ChatPage'
import SettingsPage from './pages/SettingsPage'
import LoginPage from './pages/LoginPage'
import AuthCallbackPage from './pages/AuthCallbackPage'
import SpacePage from './pages/SpacePage'
import SpaceManagementPage from './pages/SpaceManagementPage'
import GroupManagementPage from './pages/GroupManagementPage'
import LibraryManagementPage from './pages/LibraryManagementPage'
import LibraryDetailPage from './pages/LibraryDetailPage'
import { useAuthStore } from './stores/authStore'
import { useUiStore } from './stores/uiStore'

export default function App() {
  const initialize = useAuthStore((s) => s.initialize)
  const themeMode = useUiStore((s) => s.themeMode)
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)')

  const effectiveMode = themeMode === 'system' ? (prefersDark ? 'dark' : 'light') : themeMode
  const theme = useMemo(() => createAppTheme(effectiveMode), [effectiveMode])

  useEffect(() => {
    initialize()
  }, [initialize])

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
              <Route path="chat" element={<ChatPage />} />
              <Route path="spaces/:spaceId" element={<SpacePage />} />
              <Route path="spaces/:spaceId/manage" element={<SpaceManagementPage />} />
              <Route path="spaces" element={<SpacePage />} />
              <Route path="libraries" element={<LibraryManagementPage />} />
              <Route path="libraries/:libraryId" element={<LibraryDetailPage />} />
              <Route path="admin/groups" element={<GroupManagementPage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="*" element={<Navigate to="/chat" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ErrorBoundary>
    </ThemeProvider>
  )
}
