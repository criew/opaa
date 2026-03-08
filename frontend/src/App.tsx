import { useEffect, useMemo } from 'react'
import { CssBaseline, ThemeProvider, useMediaQuery } from '@mui/material'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import ErrorBoundary from './components/ErrorBoundary'
import ProtectedRoute from './components/ProtectedRoute'
import { createAppTheme } from './theme/theme'
import AppShell from './layouts/AppShell'
import ChatPage from './pages/ChatPage'
import DocumentsPage from './pages/DocumentsPage'
import SettingsPage from './pages/SettingsPage'
import LoginPage from './pages/LoginPage'
import AuthCallbackPage from './pages/AuthCallbackPage'
import WorkspacePage from './pages/WorkspacePage'
import WorkspaceManagementPage from './pages/WorkspaceManagementPage'
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
              <Route path="workspaces/:workspaceId" element={<WorkspacePage />} />
              <Route path="workspaces/:workspaceId/manage" element={<WorkspaceManagementPage />} />
              <Route path="workspaces" element={<WorkspacePage />} />
              <Route path="documents" element={<DocumentsPage />} />
              <Route path="settings" element={<SettingsPage />} />
              <Route path="*" element={<Navigate to="/chat" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ErrorBoundary>
    </ThemeProvider>
  )
}
