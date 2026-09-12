import { useEffect, useMemo } from 'react'
import { CssBaseline, ThemeProvider, useMediaQuery } from '@mui/material'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import ErrorBoundary from './components/ErrorBoundary'
import ProtectedRoute from './components/ProtectedRoute'
import { createAppTheme } from './theme/theme'
import GlobalAreaLayout from './layouts/GlobalAreaLayout'
import AppShell from './layouts/AppShell'
import ChatPage from './pages/ChatPage'
import ChatRedirect from './pages/ChatRedirect'
import SettingsPage from './pages/SettingsPage'
import LoginPage from './pages/LoginPage'
import SystemLoginPage from './pages/SystemLoginPage'
import ChangePasswordPage from './pages/ChangePasswordPage'
import SetPasswordPage from './pages/SetPasswordPage'
import ForgotPasswordPage from './pages/ForgotPasswordPage'
import RegisterPage from './pages/RegisterPage'
import VerifyEmailPage from './pages/VerifyEmailPage'
import AuthCallbackPage from './pages/AuthCallbackPage'
import SpacePage from './pages/SpacePage'
import SpacesOverviewPage from './pages/SpacesOverviewPage'
import SpaceCreatePage from './pages/SpaceCreatePage'
import SpaceManagementPage from './pages/SpaceManagementPage'
import GroupManagementPage from './pages/GroupManagementPage'
import UserManagementPage from './pages/UserManagementPage'
import LibraryManagementPage from './pages/LibraryManagementPage'
import LibraryCreatePage from './pages/LibraryCreatePage'
import LibraryDetailPage from './pages/LibraryDetailPage'
import {
  FORGOT_PASSWORD_ROUTE,
  REGISTER_ROUTE,
  SET_PASSWORD_ROUTE,
  VERIFY_EMAIL_ROUTE,
} from './routes'
import { useAuthStore } from './stores/authStore'
import { useBrandingStore } from './stores/brandingStore'
import { useUiStore } from './stores/uiStore'
import { resolveThemeMode } from './theme/colorScheme'
import BrandingSettingsPage from './pages/BrandingSettingsPage'
import LlmModelManagementPage from './pages/LlmModelManagementPage'
import OidcProviderManagementPage from './pages/OidcProviderManagementPage'
import SearchIndexingAdminPage from './pages/SearchIndexingAdminPage'
import MailSettingsPage from './pages/MailSettingsPage'

const ADMIN_SECTIONS = [
  { label: 'Allgemein & Branding', to: '/admin/branding' },
  // „Benutzer & Gruppen" als zwei Einträge statt als Tabs auf einer Seite (#1541): die
  // Sekundärspalte *ist* die Bereichsnavigation, und ein Tab-Paar darüber wäre eine zweite
  // Navigation für dieselbe Entscheidung. Jedes Ziel bleibt so direkt verlinkbar.
  { label: 'Benutzer', to: '/admin/users' },
  { label: 'Gruppen', to: '/admin/groups' },
  { label: 'Modelle', to: '/admin/models' },
  { label: 'Identitätsanbieter', to: '/admin/identity-providers' },
  { label: 'E-Mail', to: '/admin/mail' },
  { label: 'Suche & Indexierung', to: '/admin/search' },
]

/**
 * A regular user deep-linking into /admin/* gets the bare frame: showing them the full
 * administration navigation with "nicht freigegeben" destinations helps no one
 * (#800, review #794 finding 3). The pages keep their own role gates.
 */
function AdminAreaLayout() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  return isSystemAdmin ? (
    <GlobalAreaLayout title="Administration" sections={ADMIN_SECTIONS} />
  ) : (
    <GlobalAreaLayout />
  )
}

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
            {/* ADR-0033, Entscheidung 4/5: reachable at all times - the way back into an
                installation whose last identity provider is misconfigured. */}
            <Route path="/login/system" element={<SystemLoginPage />} />
            {/* Outside the application shell on purpose: while a password change is owed, every
                route but /api/v1/auth/local/* answers 403 (ADR-0033, Entscheidung 8). */}
            <Route path="/account/password" element={<ChangePasswordPage />} />
            {/* The self-service pages of local accounts (#1540), public like the sign-in page.
                ADR-0033, Entscheidung 11: the two link targets work whatever the switches say -
                a link from an invitation or an administrative reset must never run into a
                redirect - while /register and /forgot-password are pages only where the
                installation offers the flow (the pages redirect to /login otherwise). */}
            <Route path={SET_PASSWORD_ROUTE} element={<SetPasswordPage />} />
            <Route path={VERIFY_EMAIL_ROUTE} element={<VerifyEmailPage />} />
            <Route path={FORGOT_PASSWORD_ROUTE} element={<ForgotPasswordPage />} />
            <Route path={REGISTER_ROUTE} element={<RegisterPage />} />
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

              <Route path="spaces/:spaceId/chats/:chatId" element={<ChatPage />} />
              <Route path="spaces/:spaceId" element={<SpacePage />} />
              <Route path="spaces/:spaceId/manage" element={<SpaceManagementPage />} />
              {/* No space selected yet (#809): overview and create wizard render in the
                  bare global frame; the navy column appears only inside a chosen space. */}
              <Route element={<GlobalAreaLayout />}>
                <Route path="spaces" element={<SpacesOverviewPage />} />
                <Route path="spaces/new" element={<SpaceCreatePage />} />
              </Route>
              {/* The library catalog is a global area too (#789, Schlussnotiz von
                  Mockup-Abschnitt 2): bare global frame, no secondary column. */}
              <Route element={<GlobalAreaLayout />}>
                <Route path="libraries" element={<LibraryManagementPage />} />
                <Route path="libraries/new" element={<LibraryCreatePage />} />
                <Route path="libraries/:libraryId" element={<LibraryDetailPage />} />
              </Route>
              {/* Global areas render inside the frame from mockup 2b (#787): no space
                  column, a light secondary column with the area navigation instead. */}
              <Route element={<AdminAreaLayout />}>
                {/* Die beiden Bereiche der Benutzerseite sind Routen wie bei der E-Mail-Seite
                    (#1601): der Sprung aus dem Auflagen-Hinweis landet in der Kontenliste, der aus
                    der Anmeldeseite in den Einstellungen, und ein Neuladen behält den Bereich. */}
                <Route
                  path="admin/users"
                  element={<Navigate to="/admin/users/accounts" replace />}
                />
                <Route path="admin/users/:tab" element={<UserManagementPage />} />
                <Route path="admin/groups" element={<GroupManagementPage />} />
                <Route path="admin/branding" element={<BrandingSettingsPage />} />
                <Route path="admin/models" element={<LlmModelManagementPage />} />
                <Route path="admin/identity-providers" element={<OidcProviderManagementPage />} />
                {/* Die beiden Bereiche der E-Mail-Seite sind Routen, damit ein Verweis auf die
                    Vorlagenverwaltung dort landet (#1542). */}
                <Route path="admin/mail" element={<Navigate to="/admin/mail/server" replace />} />
                <Route path="admin/mail/:tab" element={<MailSettingsPage />} />
                <Route path="admin/search" element={<SearchIndexingAdminPage />} />
              </Route>
              {/* Mockup 2c (#788): the user settings render in the bare global frame -
                  no space column, no secondary column. */}
              <Route element={<GlobalAreaLayout />}>
                <Route path="settings" element={<SettingsPage />} />
              </Route>
              <Route path="*" element={<Navigate to="/chat" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </ErrorBoundary>
    </ThemeProvider>
  )
}
